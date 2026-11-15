import Foundation
import Testing
@testable import OzenKit

@Suite("A replay takes the place of the line the failed engine left half written")
@MainActor
struct ReplayHalfLineTests {
    private func rig() -> (CaptionPipeline, FakeAudioCapturer, FakeEngine, FakeEngine) {
        let cloud = FakeEngine(kind: .cloud)
        let phone = FakeEngine(kind: .whisperKit)
        let audio = FakeAudioCapturer()
        let pipeline = CaptionPipeline(
            audio: audio,
            engineFactory: { $0.engine == .cloud ? cloud : phone },
            embedder: FakeEmbedder(),
            recovery: .disabled
        )
        return (pipeline, audio, cloud, phone)
    }

    private var cloudSettings: AppSettings {
        var settings = AppSettings.default
        settings.engine = .cloud
        return settings
    }

    private func said(_ pipeline: CaptionPipeline, _ audio: FakeAudioCapturer, seconds: Double, level: Float) async {
        let before = pipeline.stats.audioSecondsReceived
        for _ in 0..<Int(seconds * 10) {
            audio.push((0..<1_600).map { $0 % 2 == 0 ? level : -level })
        }
        #expect(await eventually { pipeline.stats.audioSecondsReceived >= before + seconds - 0.01 })
    }

    private func dropAndReplay(_ pipeline: CaptionPipeline, _ audio: FakeAudioCapturer, _ cloud: FakeEngine, _ phone: FakeEngine) async {
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "gone"))
        #expect(await eventually { pipeline.isCoveringForCloud && pipeline.phase.isListening })
        audio.push([Float](repeating: 0.5, count: 1_600))
        #expect(await eventually { phone.samplesSeen.contains(0.5) })
    }

    @Test("a half line inside the replayed audio is replaced, on screen and in what History saves")
    func replacesTheHalfLine() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 3, level: 0.1)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "line A is done", isFinal: true, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
        await said(pipeline, audio, seconds: 6, level: 0.2)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "line B starts", isFinal: false, timestamp: 1_001))
        #expect(await eventually { pipeline.segments.count == 2 })
        await dropAndReplay(pipeline, audio, cloud, phone)
        #expect(pipeline.segments.map(\.text).first == "line A is done")
        #expect(pipeline.segments.last?.text.hasPrefix("line B starts") == true)

        phone.emit(TranscriptToken(utteranceID: UUID(), text: "line B starts and ends", isFinal: true, timestamp: 2_000))
        #expect(await eventually { pipeline.segments.last?.text == "line B starts and ends" })
        #expect(pipeline.segments.map(\.text) == ["line A is done", "line B starts and ends"])
    }

    @Test("a half line that began before the replayed audio keeps its words")
    func keepsAHalfLineFromBeforeTheWindow() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 5, level: 0.2)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "early words", isFinal: false, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
        await said(pipeline, audio, seconds: 35, level: 0.2)
        await dropAndReplay(pipeline, audio, cloud, phone)

        phone.emit(TranscriptToken(utteranceID: UUID(), text: "later words", isFinal: true, timestamp: 2_000))
        #expect(await eventually { pipeline.segments.count == 2 })
        #expect(pipeline.segments.first?.text.hasPrefix("early words") == true)
        #expect(pipeline.segments.last?.text == "later words")
    }

    @Test("with nothing to replay the half line stays and the new line follows it")
    func noReplayChangesNothing() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 5, level: 0)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "half line", isFinal: false, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
        await dropAndReplay(pipeline, audio, cloud, phone)

        phone.emit(TranscriptToken(utteranceID: UUID(), text: "new line", isFinal: true, timestamp: 2_000))
        #expect(await eventually { pipeline.segments.count == 2 })
        #expect(pipeline.segments.first?.text.hasPrefix("half line") == true)
        #expect(pipeline.segments.last?.text == "new line")
    }

    @Test("a replayed line the silence guard refuses leaves the half line in place")
    func refusedReplayKeepsTheHalfLine() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 5, level: 0.2)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "half line", isFinal: false, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
        await dropAndReplay(pipeline, audio, cloud, phone)

        phone.emit(TranscriptToken(utteranceID: UUID(), text: "תודה. תודה. תודה.", isFinal: true, timestamp: 2_000))
        try? await Task.sleep(for: .milliseconds(300))
        #expect(pipeline.segments.map(\.text).count == 1)
        #expect(pipeline.segments.first?.text.hasPrefix("half line") == true)
    }

    @Test("a line the failing engine itself marked cut off is not shown twice: the replay's overlap is trimmed as before")
    func engineMarkedCutOffLineIsNotDoubled() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 5, level: 0.2)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "line B starts…", isFinal: true, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
        await dropAndReplay(pipeline, audio, cloud, phone)

        phone.emit(TranscriptToken(utteranceID: UUID(), text: "line B starts and ends", isFinal: true, timestamp: 2_000))
        #expect(await eventually { pipeline.segments.count == 2 })
        #expect(pipeline.segments.map(\.text) == ["line B starts…", "and ends"])
    }

    @Test("more than eight half lines inside the replayed audio are all replaced")
    func manyHalfLinesAreAllReplaced() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 5, level: 0.2)
        for number in 1...10 {
            cloud.emit(TranscriptToken(utteranceID: UUID(), text: "open \(number)", isFinal: false, timestamp: 1_000))
        }
        #expect(await eventually { pipeline.segments.count == 10 })
        await dropAndReplay(pipeline, audio, cloud, phone)

        phone.emit(TranscriptToken(utteranceID: UUID(), text: "everything again", isFinal: true, timestamp: 2_000))
        #expect(await eventually { pipeline.segments.last?.text == "everything again" })
        #expect(pipeline.segments.map(\.text) == ["everything again"])
    }

    private func liveAfterReplay(_ pipeline: CaptionPipeline, _ audio: FakeAudioCapturer, _ phone: FakeEngine, seconds: Double) async {
        let heard = phone.samplesSeen.count
        await said(pipeline, audio, seconds: seconds, level: 0.2)
        #expect(await eventually { phone.samplesSeen.count >= heard + Int(seconds * 16_000) })
    }

    @Test("a replay that yields no words keeps the half line when live words follow")
    func silentReplayKeepsTheHalfLine() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 5, level: 0.2)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "half line", isFinal: false, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
        await dropAndReplay(pipeline, audio, cloud, phone)
        await liveAfterReplay(pipeline, audio, phone, seconds: 10)

        phone.emit(TranscriptToken(utteranceID: UUID(), text: "live words", isFinal: true, timestamp: 2_000))
        #expect(await eventually { pipeline.segments.count == 2 })
        #expect(pipeline.segments.first?.text.hasPrefix("half line") == true)
        #expect(pipeline.segments.last?.text == "live words")
    }

    @Test("words from the replay that arrive after a little live audio still replace the half line")
    func lateReplayWordsStillReplace() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 5, level: 0.2)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "half line", isFinal: false, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
        await dropAndReplay(pipeline, audio, cloud, phone)
        await liveAfterReplay(pipeline, audio, phone, seconds: 2)

        phone.emit(TranscriptToken(utteranceID: UUID(), text: "half line and the rest", isFinal: true, timestamp: 2_000))
        #expect(await eventually { pipeline.segments.last?.text == "half line and the rest" })
        #expect(pipeline.segments.map(\.text) == ["half line and the rest"])
    }

    @Test("a replay line that is only overlap when the grace passes still removes the half line when it adds words")
    func overlapOnlyLineInProgressAtTheGrace() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 3, level: 0.1)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "line A is done", isFinal: true, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
        await said(pipeline, audio, seconds: 6, level: 0.2)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "line B starts", isFinal: false, timestamp: 1_001))
        #expect(await eventually { pipeline.segments.count == 2 })
        await dropAndReplay(pipeline, audio, cloud, phone)

        let replayLine = UUID()
        phone.emit(TranscriptToken(utteranceID: replayLine, text: "line A is done", isFinal: false, timestamp: 2_000))
        await liveAfterReplay(pipeline, audio, phone, seconds: 10)
        phone.emit(TranscriptToken(utteranceID: replayLine, text: "line A is done and the rest", isFinal: true, timestamp: 2_001))
        #expect(await eventually { pipeline.segments.last?.text == "and the rest" })
        #expect(pipeline.segments.map(\.text) == ["line A is done", "and the rest"])
    }

    /// Counts the live chunks (level 0.3) the engine has read and parks it
    /// once it has read `parkAt` samples of them, after running `atPark`.
    private final class LiveReads: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        let resume = PrepareGate()
        var taken: Int { lock.withLock { count } }

        func hook(parkAt: Int, atPark: @escaping @Sendable () -> Void) -> @Sendable ([Float]) async -> Void {
            { chunk in
                guard chunk.first == 0.3 else { return }
                let reached = self.lock.withLock { () -> Bool in
                    self.count += chunk.count
                    return self.count == parkAt
                }
                if reached {
                    atPark()
                    await self.resume.wait()
                }
            }
        }
    }

    private func halfLineThenDropWithSlowModel(
        _ pipeline: CaptionPipeline, _ audio: FakeAudioCapturer, _ cloud: FakeEngine
    ) async {
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 3, level: 0.1)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "line A is done", isFinal: true, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
        await said(pipeline, audio, seconds: 6, level: 0.2)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "line B starts", isFinal: false, timestamp: 1_001))
        #expect(await eventually { pipeline.segments.count == 2 })
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "gone"))
        #expect(await eventually { pipeline.isCoveringForCloud && pipeline.phase.isListening })
        await said(pipeline, audio, seconds: 10, level: 0.3)
    }

    @Test("a model that reads the replay late, after more than the grace of live audio was captured, still replaces the half line")
    func slowModelStillReplaces() async {
        let (pipeline, audio, cloud, phone) = rig()
        let gate = PrepareGate()
        let reads = LiveReads()
        phone.streamGate = gate
        phone.afterChunk = reads.hook(parkAt: 32_000) {
            phone.emit(TranscriptToken(utteranceID: UUID(), text: "line B starts and ends", isFinal: true, timestamp: 2_000))
        }
        await halfLineThenDropWithSlowModel(pipeline, audio, cloud)
        #expect(phone.samplesSeen.isEmpty)

        await gate.open()
        #expect(await eventually { pipeline.segments.last?.text == "line B starts and ends" })
        #expect(reads.taken == 32_000)
        #expect(Double(reads.taken) < Self.graceSamples)
        #expect(pipeline.segments.map(\.text) == ["line A is done", "line B starts and ends"])
        await reads.resume.open()
        #expect(await eventually { reads.taken == 160_000 })
        #expect(pipeline.segments.map(\.text) == ["line A is done", "line B starts and ends"])
    }

    @Test("a replay with no words keeps the half line once the model has taken more than the grace of live audio")
    func slowModelWithNoWordsKeepsTheHalfLine() async {
        let (pipeline, audio, cloud, phone) = rig()
        let gate = PrepareGate()
        let reads = LiveReads()
        phone.streamGate = gate
        phone.afterChunk = reads.hook(parkAt: 160_000) {}
        await halfLineThenDropWithSlowModel(pipeline, audio, cloud)

        await gate.open()
        #expect(await eventually { reads.taken == 160_000 })
        #expect(Double(reads.taken) > Self.graceSamples)
        phone.emit(TranscriptToken(utteranceID: UUID(), text: "live words", isFinal: true, timestamp: 2_000))
        #expect(await eventually { pipeline.segments.count == 3 })
        #expect(pipeline.segments.map(\.text).first == "line A is done")
        #expect(pipeline.segments[1].text.hasPrefix("line B starts"))
        #expect(pipeline.segments.last?.text == "live words")
        await reads.resume.open()
    }

    private static let graceSamples = CaptionPipeline.replayWordsGraceSeconds * 16_000
}
