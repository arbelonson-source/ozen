import Foundation
import Testing
@testable import OzenKit

@Suite("Cloud captions that fail hand the lost speech to the phone's model")
@MainActor
struct CloudReplayTests {
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

    private func speech(seconds: Double, level: Float) -> [[Float]] {
        (0..<Int(seconds * 10)).map { _ in
            (0..<1_600).map { $0 % 2 == 0 ? level : -level }
        }
    }

    private func said(_ pipeline: CaptionPipeline, _ audio: FakeAudioCapturer, seconds: Double, level: Float) async {
        let before = pipeline.stats.audioSecondsReceived
        for chunk in speech(seconds: seconds, level: level) { audio.push(chunk) }
        #expect(await eventually { pipeline.stats.audioSecondsReceived >= before + seconds - 0.01 })
    }

    private func fail(_ cloud: FakeEngine, _ pipeline: CaptionPipeline, _ kind: EngineUnavailability.Kind) async {
        cloud.endStream(throwing: EngineUnavailability(kind: kind, detail: "gone"))
        #expect(await eventually { pipeline.isCoveringForCloud && pipeline.phase.isListening })
    }

    private func finishedLineA(_ pipeline: CaptionPipeline, _ audio: FakeAudioCapturer, _ cloud: FakeEngine) async {
        await pipeline.start(settings: cloudSettings)
        #expect(pipeline.phase.isListening)
        await said(pipeline, audio, seconds: 3, level: 0.1)
        cloud.emit(TranscriptToken(utteranceID: UUID(), text: "line A is done", isFinal: true, timestamp: 1_000))
        #expect(await eventually { pipeline.segments.count == 1 })
    }

    @Test("a cloud that finished line A and fails in the middle of line B: the phone hears B and not A again")
    func replaysTheSentenceThatWasCutOff() async {
        for kind in [EngineUnavailability.Kind.noInternet, .cloudKeyNeeded, .cloudOutOfCredit] {
            let (pipeline, audio, cloud, phone) = rig()
            await finishedLineA(pipeline, audio, cloud)
            await said(pipeline, audio, seconds: 6, level: 0.2)
            await fail(cloud, pipeline, kind)
            audio.push([Float](repeating: 0.5, count: 1_600))
            #expect(await eventually { phone.samplesSeen.contains(0.5) }, "\(kind)")

            let heard = phone.samplesSeen
            let live = heard.firstIndex(of: 0.5) ?? 0
            #expect(heard[..<live].filter { abs($0) == 0.2 }.count == 96_000, "\(kind)")
            #expect(heard[..<live].filter { abs($0) == 0.1 }.count <= 32_000, "\(kind)")
            phone.emit(TranscriptToken(utteranceID: UUID(), text: "line B is done", isFinal: true, timestamp: 2_000))
            #expect(await eventually { pipeline.segments.count == 2 }, "\(kind)")
            #expect(pipeline.segments.map(\.text) == ["line A is done", "line B is done"], "\(kind)")
        }
    }

    @Test("a failure with nothing finished replays at most the replay window")
    func nothingFinishedIsCapped() async {
        let (pipeline, audio, cloud, phone) = rig()
        await pipeline.start(settings: cloudSettings)
        await said(pipeline, audio, seconds: 40, level: 0.2)
        await fail(cloud, pipeline, .noInternet)
        audio.push([Float](repeating: 0.5, count: 1_600))
        #expect(await eventually { phone.samplesSeen.count >= 1_600 + 320_000 })
        try? await Task.sleep(for: .milliseconds(100))

        #expect(phone.samplesSeen.count == 320_000 + 1_600)
    }
}
