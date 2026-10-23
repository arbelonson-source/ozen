import AVFoundation
import Foundation
import Testing
import OzenKit
@testable import OzenPlatform

private final class ReaderReadLog: @unchecked Sendable {
    private let lock = NSLock()
    private var counts: [AVAudioFrameCount] = []
    private let failAt: Int?

    init(failingAt: Int? = nil) { failAt = failingAt }

    var requested: [AVAudioFrameCount] { lock.withLock { counts } }

    func record(_ count: AVAudioFrameCount) throws {
        let number = lock.withLock { () -> Int in
            counts.append(count)
            return counts.count
        }
        if let failAt, number >= failAt { throw ReaderTestFailure() }
    }
}

private struct ReaderTestFailure: Error {}

private enum ReaderFiles {
    static let tone = 440.0

    static func url(_ fileExtension: String) -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("reader-\(UUID().uuidString).\(fileExtension)")
    }

    static func sine(_ index: Int, rate: Double, amplitude: Float = 0.3) -> Float {
        amplitude * Float(sin(2 * Double.pi * tone * Double(index) / rate))
    }

    static func write(to url: URL, seconds: Int, rate: Double, channels: AVAudioChannelCount, settings: [String: Any]? = nil, leftSilent: Bool = false) throws {
        let format = try #require(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate, channels: channels, interleaved: false))
        let file = try AVAudioFile(forWriting: url, settings: settings ?? format.settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        let frames = Int(rate)
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)))
        buffer.frameLength = AVAudioFrameCount(frames)
        let data = try #require(buffer.floatChannelData)
        for second in 0..<seconds {
            for index in 0..<frames {
                let value = sine(second * frames + index, rate: rate)
                for channel in 0..<Int(channels) { data[channel][index] = leftSilent && channel == 0 ? 0 : value }
            }
            try file.write(from: buffer)
        }
    }

    static func aac(seconds: Int, leftSilent: Bool = false) throws -> URL {
        let url = url("m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 48_000,
            AVNumberOfChannelsKey: 2,
            AVEncoderBitRateKey: 128_000,
        ]
        try write(to: url, seconds: seconds, rate: 48_000, channels: 2, settings: settings, leftSilent: leftSilent)
        return url
    }

    static func all(_ reader: RecordingAudioReader) throws -> [[Float]] {
        var chunks: [[Float]] = []
        while let chunk = try reader.nextChunk() { chunks.append(chunk) }
        return chunks
    }

    static func crossings(_ samples: ArraySlice<Float>) -> Int {
        zip(samples, samples.dropFirst()).filter { ($0 < 0) != ($1 < 0) }.count
    }

    static func peak(_ samples: [Float]) -> Float {
        samples.map { abs($0) }.max() ?? 0
    }
}

@Suite("Reading a recording in pieces")
struct RecordingAudioReaderTests {
    @Test("three minutes come out as six chunks of at most thirty seconds, in order and whole")
    func longFile() async throws {
        let url = ReaderFiles.url("caf")
        defer { try? FileManager.default.removeItem(at: url) }
        try ReaderFiles.write(to: url, seconds: 180, rate: 16_000, channels: 1)
        let reader = try await RecordingAudioReader.open(url)
        #expect(abs((reader.durationSeconds ?? 0) - 180) < 0.1)
        let chunks = try ReaderFiles.all(reader)
        #expect(chunks.count == 6)
        #expect(chunks.allSatisfy { $0.count <= 480_000 })
        #expect(chunks.dropLast().allSatisfy { $0.count == 480_000 })
        let samples = chunks.flatMap { $0 }
        #expect(abs(samples.count - 2_880_000) <= 1_600)
        let wrong = stride(from: 0, to: min(samples.count, 2_880_000), by: 997).filter {
            abs(samples[$0] - ReaderFiles.sine($0, rate: 16_000)) > 0.001
        }
        #expect(wrong.isEmpty, "\(wrong.count) samples differ, the first at \(wrong.first ?? -1)")
        #expect(try reader.nextChunk() == nil)
    }

    @Test("nothing is read ahead: one chunk asks the file for one chunk's worth, a second at a time")
    func readsOnlyWhatIsAskedFor() async throws {
        let url = ReaderFiles.url("caf")
        defer { try? FileManager.default.removeItem(at: url) }
        try ReaderFiles.write(to: url, seconds: 180, rate: 16_000, channels: 1)
        let log = ReaderReadLog()
        let reader = try await RecordingAudioReader.open(url, chunkSeconds: 30) { file, buffer, count in
            try log.record(count)
            try file.read(into: buffer, frameCount: count)
        }
        let first = try #require(try reader.nextChunk())
        #expect(first.count == 480_000)
        #expect((30...32).contains(log.requested.count))
        #expect(log.requested.allSatisfy { $0 <= 16_000 })
        _ = try reader.nextChunk()
        #expect((60...64).contains(log.requested.count))
    }

    @Test("a phone recording in AAC, stereo at 48 kHz, comes out as the same length and the same tone at 16 kHz")
    func compressedStereo() async throws {
        let url = try ReaderFiles.aac(seconds: 20)
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = try await RecordingAudioReader.open(url)
        let samples = try ReaderFiles.all(reader).flatMap { $0 }
        #expect(abs(samples.count - 320_000) <= 1_600, "\(samples.count) samples")
        let middle = samples[160_000..<176_000]
        #expect(abs(ReaderFiles.crossings(middle) - 880) <= 12, "\(ReaderFiles.crossings(middle)) crossings")
        #expect((0.25...0.65).contains(ReaderFiles.peak(Array(middle))))
    }

    @Test("a stereo recording with the voice on the right channel only is still heard")
    func rightChannelOnly() async throws {
        let url = try ReaderFiles.aac(seconds: 5, leftSilent: true)
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = try await RecordingAudioReader.open(url)
        let samples = try ReaderFiles.all(reader).flatMap { $0 }
        let middle = samples[32_000..<48_000]
        #expect(abs(ReaderFiles.crossings(middle) - 880) <= 12, "\(ReaderFiles.crossings(middle)) crossings")
        #expect((0.1...0.65).contains(ReaderFiles.peak(Array(middle))))
    }

    @Test("a WhatsApp voice note, Ogg Opus, is read like any recording")
    func oggOpusVoiceNote() async throws {
        let url = try SpeakerFixtureLoading.url(named: "voice-note.opus")
        let reader = try await RecordingAudioReader.open(url)
        #expect(abs((reader.durationSeconds ?? 0) - 5) < 0.1)
        let samples = try ReaderFiles.all(reader).flatMap { $0 }
        #expect(abs(samples.count - 80_000) <= 1_600, "\(samples.count) samples")
        let middle = samples[32_000..<48_000]
        #expect(abs(ReaderFiles.crossings(middle) - 880) <= 12, "\(ReaderFiles.crossings(middle)) crossings")
        #expect((0.2...0.4).contains(ReaderFiles.peak(Array(middle))))
    }

    @Test("the sound of a movie file is read the same way")
    func movie() async throws {
        let audio = try ReaderFiles.aac(seconds: 20)
        let movie = ReaderFiles.url("mov")
        defer {
            try? FileManager.default.removeItem(at: audio)
            try? FileManager.default.removeItem(at: movie)
        }
        try await ReaderMovies.remux(audio: audio, to: movie)
        let reader = try await RecordingAudioReader.open(movie)
        #expect(abs((reader.durationSeconds ?? 0) - 20) < 0.2)
        let samples = try ReaderFiles.all(reader).flatMap { $0 }
        #expect(abs(samples.count - 320_000) <= 3_200, "\(samples.count) samples")
        let middle = samples[160_000..<176_000]
        #expect(abs(ReaderFiles.crossings(middle) - 880) <= 12, "\(ReaderFiles.crossings(middle)) crossings")
        #expect((0.25...0.65).contains(ReaderFiles.peak(Array(middle))))
    }

    @Test("a movie with no sound says so")
    func silentMovie() async throws {
        let movie = ReaderFiles.url("mov")
        defer { try? FileManager.default.removeItem(at: movie) }
        try await ReaderMovies.picturesOnly(at: movie)
        await #expect(throws: RecordingReadProblem.noAudio) {
            _ = try await RecordingAudioReader.open(movie)
        }
    }

    @Test("a file that is not a recording is unreadable, whatever it is called", arguments: ["m4a", "mov", "wav", "mp3"])
    func notMedia(fileExtension: String) async throws {
        let url = ReaderFiles.url(fileExtension)
        defer { try? FileManager.default.removeItem(at: url) }
        try Data("not a recording".utf8).write(to: url)
        await #expect(throws: RecordingReadProblem.unreadable) {
            _ = try await RecordingAudioReader.open(url)
        }
    }

    @Test("an audio file with nothing in it has no sound")
    func empty() async throws {
        let url = ReaderFiles.url("caf")
        defer { try? FileManager.default.removeItem(at: url) }
        try ReaderFiles.write(to: url, seconds: 0, rate: 16_000, channels: 1)
        await #expect(throws: RecordingReadProblem.noAudio) {
            _ = try await RecordingAudioReader.open(url)
        }
    }

    @Test("a file that goes bad part way gives what was read, then says it stalled")
    func goesBad() async throws {
        let url = ReaderFiles.url("caf")
        defer { try? FileManager.default.removeItem(at: url) }
        try ReaderFiles.write(to: url, seconds: 180, rate: 16_000, channels: 1)
        let log = ReaderReadLog(failingAt: 40)
        let reader = try await RecordingAudioReader.open(url, chunkSeconds: 30) { file, buffer, count in
            try log.record(count)
            try file.read(into: buffer, frameCount: count)
        }
        let first = try #require(try reader.nextChunk())
        let second = try #require(try reader.nextChunk())
        #expect(first.count == 480_000)
        #expect(abs(second.count - 9 * 16_000) <= 200, "\(second.count) samples, \(log.requested.count) reads")
        #expect(throws: RecordingReadProblem.stalled) { _ = try reader.nextChunk() }
        #expect(try reader.nextChunk() == nil)
    }

    @Test("a file that cannot be read at all is unreadable, not stalled")
    func failsAtOnce() async throws {
        let url = ReaderFiles.url("caf")
        defer { try? FileManager.default.removeItem(at: url) }
        try ReaderFiles.write(to: url, seconds: 5, rate: 16_000, channels: 1)
        let log = ReaderReadLog(failingAt: 1)
        let reader = try await RecordingAudioReader.open(url, chunkSeconds: 30) { file, buffer, count in
            try log.record(count)
            try file.read(into: buffer, frameCount: count)
        }
        #expect(throws: RecordingReadProblem.unreadable) { _ = try reader.nextChunk() }
    }
}

private enum ReaderMovies {
    static func remux(audio: URL, to movie: URL) async throws {
        let asset = AVURLAsset(url: audio)
        let track = try #require(try await asset.loadTracks(withMediaType: .audio).first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        reader.add(output)
        let writer = try AVAssetWriter(outputURL: movie, fileType: .mov)
        let hint = try await track.load(.formatDescriptions).first
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: nil, sourceFormatHint: hint)
        writer.add(input)
        let reading = reader.startReading()
        let writing = writer.startWriting()
        #expect(reading && writing)
        writer.startSession(atSourceTime: .zero)
        var waits = 0
        while reader.status == .reading, waits < 2_000 {
            if input.isReadyForMoreMediaData, let sample = output.copyNextSampleBuffer() {
                input.append(sample)
            } else {
                waits += 1
                try await Task.sleep(for: .milliseconds(5))
            }
        }
        input.markAsFinished()
        await writer.finishWriting()
        #expect(writer.status == .completed, "\(String(describing: writer.error))")
    }

    static func picturesOnly(at movie: URL) async throws {
        let writer = try AVAssetWriter(outputURL: movie, fileType: .mov)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: 160,
            AVVideoHeightKey: 128,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 160,
            kCVPixelBufferHeightKey as String: 128,
        ])
        #expect(writer.canAdd(input))
        writer.add(input)
        let writing = writer.startWriting()
        #expect(writing, "\(String(describing: writer.error))")
        writer.startSession(atSourceTime: .zero)
        var pixels: CVPixelBuffer?
        CVPixelBufferCreate(nil, 160, 128, kCVPixelFormatType_32BGRA, nil, &pixels)
        let frame = try #require(pixels)
        var waits = 0
        for index in 0..<3 {
            while !input.isReadyForMoreMediaData, waits < 2_000 {
                waits += 1
                try await Task.sleep(for: .milliseconds(5))
            }
            let appended = adaptor.append(frame, withPresentationTime: CMTime(value: Int64(index), timescale: 10))
            #expect(appended)
        }
        input.markAsFinished()
        await writer.finishWriting()
        #expect(writer.status == .completed, "\(String(describing: writer.error))")
    }
}
