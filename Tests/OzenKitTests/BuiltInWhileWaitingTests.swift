import Foundation
import Testing
@testable import OzenKit

@Suite("What the phone's built-in recognition offers while the model waits for Wi-Fi")
struct BuiltInWhileWaitingTests {
    private let waiting = PipelinePhase.failed(PipelineFailure(
        kind: .engineUnavailable,
        detail: "626 MB to download, waiting for Wi-Fi",
        engineUnavailability: EngineUnavailability(kind: .waitingForWiFi, detail: "cellular or Low Data Mode", downloadMegabytes: 626)
    ))

    private func chosen(_ languageCode: String = "he", engine: TranscriptionEngineKind = .whisperKit, allowCellular: Bool = false) -> AppSettings {
        var settings = AppSettings.default
        settings.engine = engine
        settings.languageCode = languageCode
        settings.allowCellularModelDownload = allowCellular
        return settings
    }

    @Test("offered only where the built-in recognition works on the phone alone for the spoken language")
    func languageAndOnDeviceSupport() {
        let cases: [(language: String, onDevice: Set<String>, expected: BuiltInWhileWaiting.Offer)] = [
            ("he", ["he", "en"], .builtInOrCellularDownload),
            ("he", ["en"], .cellularDownload),
            ("en", ["en"], .builtInOrCellularDownload),
            ("en", [], .cellularDownload),
            ("ru", ["he", "en"], .cellularDownload),
            ("ru", ["ru"], .builtInOrCellularDownload),
        ]
        for item in cases {
            let offer = BuiltInWhileWaiting.offer(phase: waiting, chosen: chosen(item.language), network: .cellular) { item.onDevice.contains($0) }
            #expect(offer == item.expected, "\(item.language) with \(item.onDevice.sorted())")
        }
    }

    @Test("the language asked about is the one spoken, whatever the app shows")
    func asksAboutTheSpokenLanguage() {
        var asked: [String] = []
        _ = BuiltInWhileWaiting.offer(phase: waiting, chosen: chosen("ar"), network: .cellular) { asked.append($0); return true }
        _ = BuiltInWhileWaiting.offer(phase: waiting, chosen: chosen("fr"), network: .cellular) { asked.append($0); return true }
        #expect(asked == ["ar", "fr"])
    }

    @Test("offered while the connection still can't take the download; on Wi-Fi the download is about to start by itself")
    func network() {
        let cases: [(network: NetworkConditions?, allowCellular: Bool, expected: BuiltInWhileWaiting.Offer)] = [
            (.cellular, false, .builtInOrCellularDownload),
            (NetworkConditions(isConnected: true, isConstrained: true), false, .builtInOrCellularDownload),
            (.offline, false, .builtInOrCellularDownload),
            (nil, false, .builtInOrCellularDownload),
            (.wifi, false, .cellularDownload),
            (.cellular, true, .cellularDownload),
        ]
        for item in cases {
            let offer = BuiltInWhileWaiting.offer(phase: waiting, chosen: chosen(allowCellular: item.allowCellular), network: item.network) { _ in true }
            #expect(offer == item.expected, "\(String(describing: item.network)) cellular allowed: \(item.allowCellular)")
        }
    }

    @Test("nothing is offered unless captions are stopped waiting for Wi-Fi")
    func downloadState() {
        let others: [PipelinePhase] = [
            .idle,
            .listening,
            .paused,
            .preparingEngine(EnginePreparationProgress(stage: .downloadingModel, fraction: 0.4)),
            .preparingEngine(EnginePreparationProgress(stage: .loadingModel, isFirstTime: true)),
            .failed(PipelineFailure(kind: .engineUnavailable, detail: "offline", engineUnavailability: EngineUnavailability(kind: .modelDownloadFailed, detail: "offline", downloadMegabytes: 626))),
            .failed(PipelineFailure(kind: .engineUnavailable, detail: "full", engineUnavailability: EngineUnavailability(kind: .notEnoughStorage, detail: "full", downloadMegabytes: 626))),
            .failed(PipelineFailure(kind: .microphonePermissionDenied, detail: "denied")),
        ]
        for phase in others {
            #expect(BuiltInWhileWaiting.offer(phase: phase, chosen: chosen(), network: .cellular) { _ in true } == .none, "\(phase)")
        }
    }

    @Test("only the phone's own model is waited for: with another engine chosen only the cellular download is offered")
    func chosenEngine() {
        for engine in [TranscriptionEngineKind.appleSpeech, .cloud, .homeServer] {
            #expect(BuiltInWhileWaiting.offer(phase: waiting, chosen: chosen(engine: engine), network: .cellular) { _ in true } == .cellularDownload, "\(engine)")
        }
    }

    @Test("the built-in captions on the phone alone even when the person allowed Apple's servers, and keeps the rest of the settings")
    func captionSettingsStayOnThePhone() {
        var settings = chosen("en")
        settings.allowServerFallbackForAppleSpeech = true
        settings.vocabulary = ["Ozen"]
        let builtIn = BuiltInWhileWaiting.captionSettings(for: settings)
        #expect(builtIn.engine == .appleSpeech)
        #expect(builtIn.allowServerFallbackForAppleSpeech == false)
        #expect(builtIn.languageCode == "en")
        #expect(builtIn.vocabulary == ["Ozen"])
        #expect(builtIn.whisperModelVariant == settings.whisperModelVariant)
    }

    @Test("the hand-over to the downloaded model: at the next pause while listening, at once if the built-in stopped, never while paused or starting")
    func handOver() {
        let cases: [(ready: Bool, phase: PipelinePhase, held: Bool, expected: BuiltInWhileWaiting.HandOver)] = [
            (true, .listening, false, .atNextPause),
            (true, waiting, false, .now),
            (true, .paused, false, .notYet),
            (true, .idle, false, .notYet),
            (true, .startingAudio, false, .notYet),
            (true, .preparingEngine(EnginePreparationProgress(stage: .loadingModel)), false, .notYet),
            (true, .listening, true, .notYet),
            (true, waiting, true, .notYet),
            (false, .listening, false, .notYet),
            (false, waiting, false, .notYet),
        ]
        for item in cases {
            let decision = BuiltInWhileWaiting.handOver(modelReady: item.ready, phase: item.phase, microphoneHeldElsewhere: item.held)
            #expect(decision == item.expected, "ready \(item.ready), \(item.phase), held \(item.held)")
        }
    }

    @Test("a resume keeps the built-in until the model is ready, then starts the model itself")
    func resume() {
        let settings = chosen("en")
        let waitingStill = BuiltInWhileWaiting.resumeSettings(chosen: settings, modelReady: false)
        #expect(waitingStill.keepsBuiltIn)
        #expect(waitingStill.settings == BuiltInWhileWaiting.captionSettings(for: settings))
        let ready = BuiltInWhileWaiting.resumeSettings(chosen: settings, modelReady: true)
        #expect(!ready.keepsBuiltIn)
        #expect(ready.settings == settings)
    }
}
