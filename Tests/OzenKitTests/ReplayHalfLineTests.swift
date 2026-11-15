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
}
