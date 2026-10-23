import AVFoundation
import Foundation
import OzenKit
import UniformTypeIdentifiers

protocol RecordingPieceSource: AnyObject, Sendable {
    func nextPiece() throws -> [Float]?
}

/// Reads a recording, an audio file or the sound of a video, as 16 kHz mono
/// samples in pieces of at most `chunkSeconds`. Nothing is read until the
/// next piece is asked for, so an hour of lecture is never in memory at once.
///
/// A file that cannot be opened throws `RecordingReadProblem.unreadable`, one
/// with no sound `.noAudio`. A file that goes bad part of the way through
/// delivers what was read and then throws `.stalled`.
public final class RecordingAudioReader: @unchecked Sendable {
    public static let sampleRate = 16_000.0
    public static let chunkSeconds = 30.0

    public let durationSeconds: Double?
    private let source: any RecordingPieceSource
    private let chunkSamples: Int
    private let lock = NSLock()
    private var pending: [Float] = []
    private var delivered = 0
    private var finished = false
    private var failure: (any Error)?

    init(source: any RecordingPieceSource, durationSeconds: Double?, chunkSeconds: Double) {
        self.source = source
        self.durationSeconds = durationSeconds
        chunkSamples = max(1, Int(chunkSeconds * Self.sampleRate))
    }

    public static func open(_ url: URL, chunkSeconds: Double = RecordingAudioReader.chunkSeconds) async throws -> RecordingAudioReader {
        try await RecordingAudioReader.open(url, chunkSeconds: chunkSeconds) { file, buffer, count in
            try file.read(into: buffer, frameCount: count)
        }
    }

    static func open(_ url: URL, chunkSeconds: Double, read: @escaping AudioFileSource.Read) async throws -> RecordingAudioReader {
        if OggOpusSource.isOgg(url) {
            let source = try OggOpusSource(url: url)
            return RecordingAudioReader(source: source, durationSeconds: source.durationSeconds, chunkSeconds: chunkSeconds)
        }
        if !isMovie(url), let file = try? AVAudioFile(forReading: url) {
            guard file.length > 0 else { throw RecordingReadProblem.noAudio }
            guard let source = AudioFileSource(file: file, read: read) else { throw RecordingReadProblem.unreadable }
            let seconds = Double(file.length) / file.processingFormat.sampleRate
            return RecordingAudioReader(source: source, durationSeconds: seconds > 0 ? seconds : nil, chunkSeconds: chunkSeconds)
        }
        return try await openMovie(url, chunkSeconds: chunkSeconds)
    }

    public func nextChunk() throws -> [Float]? {
        lock.lock()
        defer { lock.unlock() }
        while pending.count < chunkSamples, !finished {
            do {
                if let piece = try source.nextPiece() {
                    pending.append(contentsOf: piece)
                } else {
                    finished = true
                }
            } catch {
                finished = true
                failure = error
            }
        }
        if pending.isEmpty {
            guard let problem = failure else { return nil }
            failure = nil
            if let known = problem as? RecordingReadProblem, known != .stalled { throw known }
            throw delivered == 0 ? RecordingReadProblem.unreadable : RecordingReadProblem.stalled
        }
        let count = min(pending.count, chunkSamples)
        let chunk = Array(pending.prefix(count))
        pending.removeFirst(count)
        delivered += count
        return chunk
    }

    public var chunks: AsyncThrowingStream<[Float], any Error> {
        AsyncThrowingStream(unfolding: { try self.nextChunk() })
    }

    private static func isMovie(_ url: URL) -> Bool {
        UTType(filenameExtension: url.pathExtension)?.conforms(to: .movie) ?? false
    }

    private static func openMovie(_ url: URL, chunkSeconds: Double) async throws -> RecordingAudioReader {
        let asset = AVURLAsset(url: url)
        let readable: Bool
        let tracks: [AVAssetTrack]
        let duration: CMTime
        do {
            readable = try await asset.load(.isReadable)
            tracks = try await asset.load(.tracks)
            duration = try await asset.load(.duration)
        } catch {
            throw RecordingReadProblem.unreadable
        }
        guard readable, !tracks.isEmpty else { throw RecordingReadProblem.unreadable }
        let audio = tracks.filter { $0.mediaType == .audio }
        guard !audio.isEmpty else { throw RecordingReadProblem.noAudio }
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let output = AVAssetReaderAudioMixOutput(audioTracks: audio, audioSettings: settings)
        guard let reader = try? AVAssetReader(asset: asset), reader.canAdd(output) else { throw RecordingReadProblem.unreadable }
        reader.add(output)
        let seconds = duration.seconds
        return RecordingAudioReader(
            source: AssetPieceSource(reader: reader, output: output),
            durationSeconds: seconds.isFinite && seconds > 0 ? seconds : nil,
            chunkSeconds: chunkSeconds
        )
    }
}

final class AudioFileSource: RecordingPieceSource, @unchecked Sendable {
    typealias Read = @Sendable (AVAudioFile, AVAudioPCMBuffer, AVAudioFrameCount) throws -> Void

    private let file: AVAudioFile
    private let buffer: AVAudioPCMBuffer
    private let resampler: PCMResampler
    private let read: Read
    private var position: AVAudioFramePosition = 0
    private var flushed = false
    private var failure: (any Error)?

    init?(file: AVAudioFile, read: @escaping Read) {
        let blockFrames = AVAudioFrameCount(max(1, file.processingFormat.sampleRate))
        guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: blockFrames),
              let resampler = PCMResampler(from: file.processingFormat, sampleRate: RecordingAudioReader.sampleRate, maxInputFrames: blockFrames)
        else { return nil }
        self.file = file
        self.buffer = buffer
        self.resampler = resampler
        self.read = read
    }

    func nextPiece() throws -> [Float]? {
        if let failure {
            self.failure = nil
            throw failure
        }
        var samples: [Float] = []
        if position < file.length {
            let count = AVAudioFrameCount(min(AVAudioFramePosition(buffer.frameCapacity), file.length - position))
            buffer.frameLength = 0
            do {
                try read(file, buffer, count)
            } catch {
                // The converter keeps a second or two of what it was given.
                // That audio was read fine: hand it over, then the failure.
                failure = error
                position = file.length
                flushed = true
                _ = resampler.convert(nil, into: &samples)
                return samples
            }
            if buffer.frameLength > 0 {
                position += AVAudioFramePosition(buffer.frameLength)
                guard resampler.convert(buffer, into: &samples) else { throw RecordingReadProblem.stalled }
                return samples
            }
            position = file.length
        }
        guard !flushed else { return nil }
        flushed = true
        guard resampler.convert(nil, into: &samples) else { throw RecordingReadProblem.stalled }
        return samples
    }
}

/// A WhatsApp voice note: Ogg Opus, which neither AVAudioFile nor
/// AVAssetReader opens. OzenKit takes the Opus packets out of the Ogg pages;
/// Apple's Opus decoder turns about a second of them at a time into sound,
/// and the same resampler as for other files makes it 16 kHz mono. The
/// encoder's lead-in (pre-skip) and the padding after the last page's
/// granule position are cut off.
final class OggOpusSource: RecordingPieceSource, @unchecked Sendable {
    private static let packetsPerPiece = 50
    private static let largestPacket = 7_680
    private static let outputRatio = OggOpusPackets.sampleRate / Int(RecordingAudioReader.sampleRate)

    let durationSeconds: Double?
    private let handle: FileHandle
    private let packets: OggOpusPackets
    private let decoder: AVAudioConverter
    private let input: AVAudioCompressedBuffer
    private let decoded: AVAudioPCMBuffer
    private let resampler: PCMResampler
    private var toSkip: Int
    private var remaining: Int?
    private var finished = false

    static func isOgg(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        return (try? handle.read(upToCount: 4)) == Data("OggS".utf8)
    }

    init(url: URL) throws {
        guard let handle = try? FileHandle(forReadingFrom: url) else { throw RecordingReadProblem.unreadable }
        self.handle = handle
        let size = (try? handle.seekToEnd()) ?? 0
        try? handle.seek(toOffset: size > 65_536 ? size - 65_536 : 0)
        let lastGranule = (try? handle.readToEnd()).flatMap { OggOpusPackets.lastGranulePosition(in: $0) }
        try? handle.seek(toOffset: 0)
        do {
            packets = try OggOpusPackets { count in try handle.read(upToCount: count) ?? Data() }
        } catch {
            try? handle.close()
            throw RecordingReadProblem.unreadable
        }
        let head = packets.head
        let rate = Double(OggOpusPackets.sampleRate)
        var description = AudioStreamBasicDescription(
            mSampleRate: rate, mFormatID: kAudioFormatOpus, mFormatFlags: 0, mBytesPerPacket: 0, mFramesPerPacket: 960,
            mBytesPerFrame: 0, mChannelsPerFrame: UInt32(head.channels), mBitsPerChannel: 0, mReserved: 0
        )
        guard (1...2).contains(head.channels),
              let opus = AVAudioFormat(streamDescription: &description),
              let pcm = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate, channels: AVAudioChannelCount(head.channels), interleaved: false),
              let decoder = AVAudioConverter(from: opus, to: pcm),
              let decoded = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(Self.packetsPerPiece * 5_760)),
              let resampler = PCMResampler(from: pcm, sampleRate: RecordingAudioReader.sampleRate, maxInputFrames: decoded.frameCapacity)
        else {
            try? handle.close()
            throw RecordingReadProblem.unreadable
        }
        self.decoder = decoder
        self.decoded = decoded
        self.resampler = resampler
        input = AVAudioCompressedBuffer(format: opus, packetCapacity: AVAudioPacketCount(Self.packetsPerPiece), maximumPacketSize: Self.largestPacket)
        toSkip = head.preSkip / Self.outputRatio
        if let lastGranule, lastGranule > Int64(head.preSkip) {
            let samples = Int(lastGranule) - head.preSkip
            remaining = samples / Self.outputRatio
            durationSeconds = Double(samples) / rate
        } else {
            durationSeconds = nil
        }
    }

    deinit {
        try? handle.close()
    }

    func nextPiece() throws -> [Float]? {
        guard !finished else { return nil }
        let more = try fillInput()
        var samples: [Float] = []
        var handedOver = false
        while true {
            var error: NSError?
            decoded.frameLength = 0
            let status = decoder.convert(to: decoded, error: &error) { [input] _, inputStatus in
                if handedOver || input.packetCount == 0 {
                    inputStatus.pointee = more ? .noDataNow : .endOfStream
                    return nil
                }
                handedOver = true
                inputStatus.pointee = .haveData
                return input
            }
            guard status != .error, error == nil else { throw RecordingReadProblem.stalled }
            if decoded.frameLength > 0 {
                guard resampler.convert(decoded, into: &samples) else { throw RecordingReadProblem.stalled }
            }
            if status != .haveData { break }
        }
        if !more {
            guard resampler.convert(nil, into: &samples) else { throw RecordingReadProblem.stalled }
            finished = true
        }
        return trimmed(samples)
    }

    private func fillInput() throws -> Bool {
        input.packetCount = 0
        input.byteLength = 0
        guard let descriptions = input.packetDescriptions else { throw RecordingReadProblem.unreadable }
        var offset = 0
        while input.packetCount < input.packetCapacity, let packet = try packets.nextPacket() {
            guard !packet.isEmpty, packet.count <= Self.largestPacket else { continue }
            packet.withUnsafeBytes { bytes in
                input.data.advanced(by: offset).copyMemory(from: bytes.baseAddress!, byteCount: packet.count)
            }
            descriptions[Int(input.packetCount)] = AudioStreamPacketDescription(
                mStartOffset: Int64(offset), mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count)
            )
            offset += packet.count
            input.packetCount += 1
        }
        input.byteLength = UInt32(offset)
        return input.packetCount > 0
    }

    private func trimmed(_ samples: [Float]) -> [Float] {
        var kept = samples[...]
        let skipped = min(toSkip, kept.count)
        kept = kept.dropFirst(skipped)
        toSkip -= skipped
        if let left = remaining {
            kept = kept.prefix(left)
            remaining = left - kept.count
        }
        return Array(kept)
    }
}

final class AssetPieceSource: RecordingPieceSource, @unchecked Sendable {
    private let reader: AVAssetReader
    private let output: AVAssetReaderAudioMixOutput
    private var started = false

    init(reader: AVAssetReader, output: AVAssetReaderAudioMixOutput) {
        self.reader = reader
        self.output = output
    }

    deinit {
        if reader.status == .reading { reader.cancelReading() }
    }

    func nextPiece() throws -> [Float]? {
        if !started {
            started = true
            guard reader.startReading() else { throw RecordingReadProblem.unreadable }
        }
        guard let sample = output.copyNextSampleBuffer() else {
            if reader.status == .failed { throw RecordingReadProblem.stalled }
            return nil
        }
        guard let block = CMSampleBufferGetDataBuffer(sample) else { return [] }
        let count = CMBlockBufferGetDataLength(block) / MemoryLayout<Float>.size
        guard count > 0 else { return [] }
        var samples = [Float](repeating: 0, count: count)
        let status = samples.withUnsafeMutableBytes { raw in
            CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: raw.count, destination: raw.baseAddress!)
        }
        guard status == kCMBlockBufferNoErr else { throw RecordingReadProblem.stalled }
        return samples
    }
}
