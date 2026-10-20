import AVFoundation
import Foundation
import Testing
import OzenKit
@testable import OzenPlatform

private final class TranscriberFixtureEngine: TranscriptionEngine, @unchecked Sendable {
    let kind: TranscriptionEngineKind = .whisperKit
    private let lock = NSLock()
    private var heard: [[Float]] = []

    var windows: [[Float]] { lock.withLock { heard } }

    func prepare(languageCode: String, progress: @escaping @Sendable (EnginePreparationProgress) -> Void) async -> EngineAvailability {
        .available
    }

    func stream(languageCode: String, audio: AsyncStream<[Float]>) -> AsyncThrowingStream<TranscriptToken, Error> {
        AsyncThrowingStream { continuation in
            Task {
                var samples: [Float] = []
                for await piece in audio { samples += piece }
                self.lock.withLock { self.heard.append(samples) }
                continuation.yield(TranscriptToken(utteranceID: UUID(), text: "said it", isFinal: true, timestamp: 0))
                continuation.finish()
            }
        }
    }
}

@Suite("A recording turned into lines of text on the phone")
struct FileTranscriberTests {
    private func request() -> FileTranscriber.Request {
        FileTranscriber.Request(variant: "test", languageCode: "he", names: [], allowCellularDownload: false, network: { .wifi })
    }

    private func recording(_ samples: [Float]) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("transcriber-\(UUID().uuidString).caf")
        let format = try #require(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false))
        let file = try AVAudioFile(forWriting: url, settings: format.settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)))
        buffer.frameLength = AVAudioFrameCount(samples.count)
        let data = try #require(buffer.floatChannelData)
        for (index, value) in samples.enumerated() { data[0][index] = value }
        try file.write(from: buffer)
        return url
    }

    private func offsetOfMiddle(of clip: [Float], in window: [Float]) -> Int? {
        let middle = clip.count / 2
        guard window.count >= 256 else { return nil }
        return (0...(window.count - 256)).first { start in
            (0..<256).allSatisfy { abs(window[start + $0] - clip[middle + $0]) < 0.0005 }
        }
    }

    @Test("speech in the middle of a recording reaches the engine whole with a little quiet round it, and comes back as one timed line")
    func speechInTheMiddle() async throws {
        let clip = try SpeakerFixtureLoading.readSamples(named: "speakerA_clip1.wav")
        let silence = [Float](repeating: 0, count: 32_000)
        let url = try recording(silence + clip + silence)
        defer { try? FileManager.default.removeItem(at: url) }
        let engine = TranscriberFixtureEngine()
        let reader = try await RecordingAudioReader.open(url)
        let result = await FileTranscriber(makeEngine: { _ in engine }).run(request(), reader: reader, report: { _ in })
        guard case .finished(let lines, let endMissing) = result else {
            Issue.record("\(result)")
            return
        }
        #expect(!endMissing)
        #expect(lines.map(\.text) == ["said it"])
        #expect(engine.windows.count == 1)
        guard let window = engine.windows.first else { return }
        #expect(offsetOfMiddle(of: clip, in: window) != nil, "the clip is not in the \(window.count) samples the engine heard")
        #expect(window.count >= clip.count / 2)
        #expect(window.count <= clip.count + 4 * 4_096 + 6_400)
        let line = try #require(lines.first)
        #expect(line.startSeconds > 1.5 && line.startSeconds < 3.0, "starts at \(line.startSeconds)")
        #expect(line.endSeconds > 3.0 && line.endSeconds < 4.8, "ends at \(line.endSeconds)")
    }

    @Test("a recording with no speech in it says so and never wakes the engine")
    func noSpeech() async throws {
        let url = try recording([Float](repeating: 0, count: 16_000 * 6))
        defer { try? FileManager.default.removeItem(at: url) }
        let engine = TranscriberFixtureEngine()
        let reader = try await RecordingAudioReader.open(url)
        let result = await FileTranscriber(makeEngine: { _ in engine }).run(request(), reader: reader, report: { _ in })
        #expect(result == .failed(.noSpeech))
        #expect(engine.windows.isEmpty)
    }
}
