import Foundation
import Testing
@testable import OzenKit

@MainActor
@Suite("Cloud captions waiting for the cloud when no phone model can take over")
struct CloudWaitTests {
    private func pipeline(cloud: FakeEngine, detector: FakeSoundDetector? = nil) -> CaptionPipeline {
        let phone = FakeEngine(kind: .whisperKit)
        phone.pendingDownload = 819
        let captions = CaptionPipeline(
            audio: FakeAudioCapturer(),
            engineFactory: { $0.engine == .cloud ? cloud : phone },
            embedder: FakeEmbedder(),
            soundDetector: detector,
            recovery: AutoRecoveryPolicy(glitchDelays: [0.01], downloadDelays: [], microphoneTailDelays: [])
        )
        captions.cloudWaitSeconds = 0.05
        return captions
    }

    private var cloudSettings: AppSettings {
        var settings = AppSettings.default
        settings.engine = .cloud
        return settings
    }

    @Test("with no phone model, captions start again by themselves once the cloud answers")
    func comesBackByItself() async {
        let cloud = FakeEngine(kind: .cloud, availability: .unavailable(.temporarilyUnavailable, "test"))
        let captions = pipeline(cloud: cloud)
        var notes: [String] = []
        captions.onEvent = { event in notes.append(event.description) }
        await captions.start(settings: cloudSettings)
        #expect(await eventually { cloud.prepareCount >= 4 && captions.scheduledRetry == nil })
        #expect(!captions.isCoveringForCloud)
        #expect(notes.filter { $0.hasPrefix("waiting for the cloud") } == ["waiting for the cloud, asking it every 0.05 s"])
        cloud.availability = .available
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
    }

    @Test("a key problem is not asked again on a timer")
    func keyProblemNotAskedAgain() async throws {
        let cloud = FakeEngine(kind: .cloud, availability: .unavailable(.cloudKeyNeeded, "test"))
        let captions = pipeline(cloud: cloud)
        await captions.start(settings: cloudSettings)
        #expect(await eventually { captions.phase.failure != nil })
        let asked = cloud.prepareCount
        try await Task.sleep(for: .milliseconds(300))
        cloud.availability = .available
        try await Task.sleep(for: .milliseconds(300))
        #expect(cloud.prepareCount == asked)
        #expect(captions.phase.failure != nil)
    }

    @Test("a key problem that turns up during the wait ends it and is shown")
    func keyProblemDuringTheWaitIsShown() async throws {
        let cloud = FakeEngine(kind: .cloud, availability: .unavailable(.temporarilyUnavailable, "test"))
        let captions = pipeline(cloud: cloud)
        await captions.start(settings: cloudSettings)
        #expect(await eventually { cloud.prepareCount >= 4 && captions.scheduledRetry == nil })
        cloud.availability = .unavailable(.cloudKeyNeeded, "test")
        #expect(await eventually { captions.phase.failure?.engineUnavailability?.kind == .cloudKeyNeeded })
        cloud.availability = .available
        try await Task.sleep(for: .milliseconds(300))
        #expect(captions.phase.failure != nil)
    }

    @Test("stopping during the wait means nothing restarts when the cloud answers")
    func stopDuringTheWait() async throws {
        let cloud = FakeEngine(kind: .cloud, availability: .unavailable(.temporarilyUnavailable, "test"))
        let captions = pipeline(cloud: cloud)
        await captions.start(settings: cloudSettings)
        #expect(await eventually { cloud.prepareCount >= 4 && captions.scheduledRetry == nil })
        captions.stop()
        let asked = cloud.prepareCount
        cloud.availability = .available
        try await Task.sleep(for: .milliseconds(300))
        #expect(cloud.prepareCount == asked)
        #expect(!captions.phase.isListening)
    }

    @Test("a smoke alarm still alerts while captions wait for the cloud")
    func soundsWhileWaiting() async {
        let cloud = FakeEngine(kind: .cloud, availability: .unavailable(.temporarilyUnavailable, "test"))
        let detector = FakeSoundDetector()
        let captions = pipeline(cloud: cloud, detector: detector)
        await captions.start(settings: cloudSettings)
        #expect(await eventually { cloud.prepareCount >= 4 && captions.scheduledRetry == nil })
        #expect(await eventually { captions.isListeningForSoundsOnly && captions.stats.soundDetectionRunning })
        detector.push(SoundObservation(identifier: "smoke_detector", confidence: 0.9, timestamp: 100))
        #expect(await eventually { captions.soundAlerts.count == 1 })
        captions.stop()
    }
}
