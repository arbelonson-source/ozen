import Foundation
import Testing
@testable import OzenKit

@Suite("A cloud connection that never answers hands over to the phone's model after one timeout")
@MainActor
struct CloudHangingConnectionTests {
    private func rig(_ http: FakeCloudHTTP) -> (CaptionPipeline, FakeAudioCapturer, FakeEngine) {
        let cloud: any TranscriptionEngine = CloudSpeechEngine(http: http, failedSegmentPauseSeconds: 0.001, approvalSeconds: 0.01, apiKey: { "sk-test" })
        let phone = FakeEngine(kind: .whisperKit)
        let audio = FakeAudioCapturer()
        let pipeline = CaptionPipeline(
            audio: audio,
            engineFactory: { $0.engine == .cloud ? cloud : phone },
            embedder: FakeEmbedder(),
            recovery: .disabled
        )
        pipeline.cloudRecheckSeconds = 0.05
        pipeline.homeServerSwitchBackQuietSeconds = 0
        return (pipeline, audio, phone)
    }

    private var cloudSettings: AppSettings {
        var settings = AppSettings.default
        settings.engine = .cloud
        return settings
    }

    private func speak(_ audio: FakeAudioCapturer, seconds: Double, level: Float) {
        for _ in 0..<Int(seconds * 10) {
            audio.push((0..<1_600).map { $0 % 2 == 0 ? level : -level })
        }
    }

    /// Speech in steps of a live pass, until `done` or the steps run out.
    private func talk(_ audio: FakeAudioCapturer, steps: Int, until done: () -> Bool) async {
        for _ in 0..<steps where !done() {
            speak(audio, seconds: 2.1, level: 0.2)
            _ = await eventually(within: .milliseconds(300)) { done() }
        }
    }

    @Test("no answer at all: the phone's model takes over after the first timeout, with the speech replayed, and the cloud takes captions back once it answers")
    func timeoutHandsOverAtOnce() async {
        let http = FakeCloudHTTP(answers: [.text("hello")])
        let (pipeline, audio, phone) = rig(http)
        await pipeline.start(settings: cloudSettings)
        http.blackHole = true
        await talk(audio, steps: 6) { pipeline.isCoveringForCloud }
        #expect(await eventually { pipeline.isCoveringForCloud && pipeline.phase.isListening && pipeline.activeEngineKind == .whisperKit })
        #expect(http.transcriptionRequests.count == 1)

        audio.push([Float](repeating: 0.5, count: 1_600))
        #expect(await eventually { phone.samplesSeen.contains(0.5) })
        let heard = phone.samplesSeen
        let live = heard.firstIndex(of: 0.5) ?? 0
        #expect(heard[..<live].filter { abs($0) == 0.2 }.count >= 32_000)

        try? await Task.sleep(for: .milliseconds(300))
        #expect(pipeline.isCoveringForCloud)
        http.blackHole = false
        #expect(await eventually { !pipeline.isCoveringForCloud && pipeline.activeEngineKind == .cloud })
        #expect(pipeline.phase == .listening)
    }

    @Test("a refusal with an answer in it still follows the old path: every round is tried before the phone takes over")
    func refusalKeepsTheRetries() async {
        let http = FakeCloudHTTP(answers: [.status(503, "busy")])
        let (pipeline, audio, _) = rig(http)
        await pipeline.start(settings: cloudSettings)
        await talk(audio, steps: 12) { pipeline.isCoveringForCloud }
        #expect(await eventually { pipeline.isCoveringForCloud })
        #expect(http.transcriptionRequests.count >= CloudSpeechEngine.failuresBeforeStopping)
    }

    @Test("without the phone's model installed a timeout is retried as before and nothing is handed over")
    func noPhoneModelKeepsTheRetries() async {
        let http = FakeCloudHTTP(answers: [.text("hello")])
        let (pipeline, audio, phone) = rig(http)
        phone.pendingDownload = 400
        await pipeline.start(settings: cloudSettings)
        http.blackHole = true
        await talk(audio, steps: 3) { http.transcriptionRequests.count >= 2 }
        #expect(http.transcriptionRequests.count >= 2)
        #expect(!pipeline.isCoveringForCloud)
        #expect(pipeline.activeEngineKind == .cloud)
    }
}
