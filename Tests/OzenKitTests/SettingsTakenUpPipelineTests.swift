import Foundation
import Testing
@testable import OzenKit

@Suite("Saved settings taken up by the pipeline")
@MainActor
struct SettingsTakenUpPipelineTests {
    private func pipeline() -> CaptionPipeline {
        CaptionPipeline(audio: FakeAudioCapturer(), engineFactory: { FakeEngine(kind: $0.engine) }, embedder: FakeEmbedder(), recovery: .disabled)
    }

    private func saved() -> AppSettings {
        var settings = AppSettings.default
        settings.soundAlerts = SoundAlertPreferences(minimumImportance: .low, mutedIdentifiers: ["door"], sensitiveIdentifiers: ["door_bell"])
        settings.speakerProfiles = [SpeakerProfile(name: "Dana", embedding: [1, 0, 0])]
        return settings
    }

    @Test("saved settings that come back while captions are paused bring their sound alerts, the faint doorbell included, and their voices at once, and captions stay paused")
    func pausedTakesUpSoundAlertsAndVoices() async {
        let pipeline = pipeline()
        await pipeline.start(settings: .default)
        pipeline.pause()
        let restarts = pipeline.stats.engineRestarts
        let settings = saved()

        pipeline.settingsTakenUp(settings, replacing: .default)
        #expect(pipeline.soundPolicy.preferences == settings.soundAlerts)
        #expect(pipeline.soundPolicy.preferences.sensitiveIdentifiers.contains("door_bell"))
        #expect(pipeline.speakerClusters.contains { $0.name == "Dana" })
        #expect(pipeline.phase == .paused)
        #expect(pipeline.stats.engineRestarts == restarts)
    }

    @Test("saved settings that come back while captions are off bring their sound alerts without starting anything, and a voice already listened for is not added twice")
    func idleTakesUpSoundAlerts() {
        let pipeline = pipeline()
        let settings = saved()
        pipeline.enroll(profile: settings.speakerProfiles[0])

        pipeline.settingsTakenUp(settings, replacing: settings.with(soundAlerts: .init()))
        #expect(pipeline.soundPolicy.preferences == settings.soundAlerts)
        #expect(pipeline.speakerClusters.filter { $0.name == "Dana" }.count == 1)
        #expect(pipeline.phase == .idle)
        #expect(!pipeline.isListeningForSoundsOnly)
    }
}

private extension AppSettings {
    func with(soundAlerts: SoundAlertPreferences) -> AppSettings {
        var settings = self
        settings.soundAlerts = soundAlerts
        return settings
    }
}
