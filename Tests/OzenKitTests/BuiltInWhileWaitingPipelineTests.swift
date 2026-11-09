import Foundation
import Testing
@testable import OzenKit

@MainActor
@Suite("Captions from the phone's built-in recognition while the model waits for Wi-Fi")
struct BuiltInWhileWaitingPipelineTests {
    private final class Built {
        var settings: [AppSettings] = []
    }

    private func makePipeline(
        network: FakeNetworkMonitor,
        model: FakeEngine,
        builtIn: FakeEngine,
        built: Built = Built(),
        soundDetector: FakeSoundDetector? = nil
    ) -> CaptionPipeline {
        let pipeline = CaptionPipeline(
            audio: FakeAudioCapturer(),
            engineFactory: { settings in
                built.settings.append(settings)
                return settings.engine == .appleSpeech ? builtIn : model
            },
            embedder: FakeEmbedder(),
            soundDetector: soundDetector,
            recovery: .disabled,
            audioWatchdog: .disabled,
            network: network
        )
        pipeline.networkFirstReportWaitSeconds = 0.3
        pipeline.handOverCheckSeconds = 0.02
        pipeline.switchBackBreathWaitSeconds = 0.3
        return pipeline
    }

    private func waitingModel() -> FakeEngine {
        let model = FakeEngine(kind: .whisperKit)
        model.pendingDownload = 626
        model.duringPrepare = { model.pendingDownload = nil }
        return model
    }

    private var chosen: AppSettings {
        var settings = AppSettings.default
        settings.engine = .whisperKit
        settings.allowServerFallbackForAppleSpeech = true
        return settings
    }

    private func line(_ id: UUID, _ text: String, final: Bool) -> TranscriptToken {
        TranscriptToken(utteranceID: id, text: text, isFinal: final, timestamp: Date().timeIntervalSince1970)
    }

    private func onBuiltIn(_ pipeline: CaptionPipeline) async {
        await pipeline.start(settings: chosen)
        #expect(pipeline.phase.failure?.engineUnavailability?.kind == .waitingForWiFi)
        await pipeline.captionWithBuiltInWhileWaiting(settings: chosen)
        #expect(pipeline.phase == .listening)
        #expect(pipeline.activeEngineKind == .appleSpeech)
    }

    @Test("on cellular, captions start now on the built-in recognition, on the phone only, and nothing downloads", .timeLimit(.minutes(1)))
    func startsOnTheBuiltIn() async {
        let model = waitingModel()
        let built = Built()
        let pipeline = makePipeline(network: FakeNetworkMonitor(.cellular), model: model, builtIn: FakeEngine(kind: .appleSpeech), built: built)
        await onBuiltIn(pipeline)

        #expect(pipeline.isCoveringForCloud)
        #expect(pipeline.coverReason == .waitingForWiFi)
        let builtInSettings = built.settings.filter { $0.engine == .appleSpeech }
        #expect(!builtInSettings.isEmpty)
        #expect(builtInSettings.allSatisfy { !$0.allowServerFallbackForAppleSpeech }, "the built-in was allowed to send audio to Apple's servers")
        #expect(model.prepareCount == 0)
        #expect(!pipeline.waitingModelGettingReady)
    }

    @Test("a built-in recognition that can't run here leaves captions waiting for Wi-Fi, still listening for sounds", .timeLimit(.minutes(1)))
    func refusedBuiltInKeepsWaiting() async {
        let builtIn = FakeEngine(kind: .appleSpeech, availability: .unavailable(.languageNotSupportedOnDevice, "no on-device Hebrew"))
        let pipeline = makePipeline(network: FakeNetworkMonitor(.cellular), model: waitingModel(), builtIn: builtIn, soundDetector: FakeSoundDetector())
        await pipeline.start(settings: chosen)
        #expect(pipeline.isListeningForSoundsOnly)

        await pipeline.captionWithBuiltInWhileWaiting(settings: chosen)
        #expect(pipeline.phase.failure?.engineUnavailability?.kind == .waitingForWiFi)
        #expect(!pipeline.isCoveringForCloud)
        #expect(pipeline.activeSettings?.engine == .whisperKit)
        #expect(pipeline.isListeningForSoundsOnly)
    }

    @Test("once Wi-Fi is back the model gets ready behind the built-in, then captions switch to it by themselves", .timeLimit(.minutes(1)))
    func handsOverWhenTheModelIsReady() async {
        let network = FakeNetworkMonitor(.cellular)
        let model = waitingModel()
        let builtIn = FakeEngine(kind: .appleSpeech)
        let gate = PrepareGate()
        model.prepareGate = gate
        let pipeline = makePipeline(network: network, model: model, builtIn: builtIn)
        await onBuiltIn(pipeline)
        builtIn.emit(line(UUID(), "said before the model came", final: true))
        #expect(await eventually { pipeline.segments.count == 1 })

        network.change(to: .wifi)
        #expect(await eventually { model.heldPrepares == 1 })
        #expect(pipeline.waitingModelGettingReady)
        #expect(pipeline.phase == .listening)
        #expect(pipeline.activeEngineKind == .appleSpeech)
        #expect(model.cellularAllowedSeen == [false])

        await gate.open()
        #expect(await eventually { pipeline.activeEngineKind == .whisperKit && pipeline.phase == .listening })
        #expect(!pipeline.isCoveringForCloud)
        #expect(pipeline.builtInHandOvers == 1)
        #expect(!pipeline.waitingModelGettingReady)
        #expect(pipeline.segments.map(\.text) == ["said before the model came"])
    }

    @Test("the switch waits for the line being said to finish", .timeLimit(.minutes(1)))
    func handOverWaitsForThePause() async throws {
        let network = FakeNetworkMonitor(.cellular)
        let model = waitingModel()
        let builtIn = FakeEngine(kind: .appleSpeech)
        let pipeline = makePipeline(network: network, model: model, builtIn: builtIn)
        await onBuiltIn(pipeline)
        let id = UUID()
        builtIn.emit(line(id, "still talking", final: false))
        #expect(await eventually { pipeline.segments.count == 1 })

        network.change(to: .wifi)
        #expect(await eventually { model.prepareCount == 1 && !pipeline.waitingModelGettingReady })
        try await Task.sleep(for: .milliseconds(400))
        #expect(pipeline.activeEngineKind == .appleSpeech, "switched in the middle of a line")

        builtIn.emit(line(id, "still talking, and done", final: true))
        #expect(await eventually { pipeline.activeEngineKind == .whisperKit && pipeline.phase == .listening })
        #expect(pipeline.builtInHandOvers == 1)
    }

    @Test("a model that fails to get ready leaves the built-in captioning, and is tried again when the connection changes", .timeLimit(.minutes(1)))
    func failedPreparationTriesAgain() async {
        let network = FakeNetworkMonitor(.cellular)
        let model = waitingModel()
        model.duringPrepare = nil
        model.availability = .unavailable(.modelDownloadFailed, "cut off")
        let pipeline = makePipeline(network: network, model: model, builtIn: FakeEngine(kind: .appleSpeech))
        pipeline.waitingModelRetrySeconds = 600
        await onBuiltIn(pipeline)

        network.change(to: .wifi)
        #expect(await eventually { model.prepareCount == 1 && !pipeline.waitingModelGettingReady })
        #expect(pipeline.phase == .listening)
        #expect(pipeline.activeEngineKind == .appleSpeech)
        #expect(pipeline.isCoveringForCloud)

        model.availability = .available
        model.duringPrepare = { model.pendingDownload = nil }
        network.change(to: .cellular)
        network.change(to: .wifi)
        #expect(await eventually { pipeline.activeEngineKind == .whisperKit && pipeline.phase == .listening })
        #expect(pipeline.builtInHandOvers == 1)
    }

    @Test("a model that fails to get ready on Wi-Fi is tried again after a while without a change of connection", .timeLimit(.minutes(1)))
    func failedPreparationTriesAgainLater() async {
        let network = FakeNetworkMonitor(.cellular)
        let model = waitingModel()
        model.duringPrepare = nil
        model.availability = .unavailable(.modelDownloadFailed, "server hiccup")
        let pipeline = makePipeline(network: network, model: model, builtIn: FakeEngine(kind: .appleSpeech))
        pipeline.waitingModelRetrySeconds = 0.2
        await onBuiltIn(pipeline)

        network.change(to: .wifi)
        #expect(await eventually { model.prepareCount == 1 && !pipeline.waitingModelGettingReady })
        #expect(pipeline.activeEngineKind == .appleSpeech)

        model.availability = .available
        model.duringPrepare = { model.pendingDownload = nil }
        #expect(await eventually { pipeline.activeEngineKind == .whisperKit && pipeline.phase == .listening })
        #expect(model.prepareCount >= 3)
    }

    @Test("stopping captions ends the built-in, and Wi-Fi afterwards gets nothing ready", .timeLimit(.minutes(1)))
    func stopEndsIt() async throws {
        let network = FakeNetworkMonitor(.cellular)
        let model = waitingModel()
        let pipeline = makePipeline(network: network, model: model, builtIn: FakeEngine(kind: .appleSpeech))
        await onBuiltIn(pipeline)

        pipeline.stop()
        network.change(to: .wifi)
        try await Task.sleep(for: .milliseconds(200))
        #expect(model.prepareCount == 0)
        #expect(pipeline.phase == .idle)
    }

    @Test("a pause and resume keep the built-in while the model is not ready", .timeLimit(.minutes(1)))
    func resumeKeepsTheBuiltIn() async {
        let pipeline = makePipeline(network: FakeNetworkMonitor(.cellular), model: waitingModel(), builtIn: FakeEngine(kind: .appleSpeech))
        await onBuiltIn(pipeline)

        pipeline.pause(releasingAudio: true)
        await pipeline.resume(settings: chosen)
        #expect(pipeline.phase == .listening)
        #expect(pipeline.activeEngineKind == .appleSpeech)
        #expect(pipeline.isCoveringForCloud)
    }

    @Test("with the model ready while paused, a resume starts the model itself", .timeLimit(.minutes(1)))
    func resumeStartsTheReadyModel() async throws {
        let network = FakeNetworkMonitor(.cellular)
        let model = waitingModel()
        let pipeline = makePipeline(network: network, model: model, builtIn: FakeEngine(kind: .appleSpeech))
        await onBuiltIn(pipeline)

        pipeline.pause(releasingAudio: true)
        network.change(to: .wifi)
        #expect(await eventually { model.prepareCount == 1 && !pipeline.waitingModelGettingReady })
        try await Task.sleep(for: .milliseconds(100))
        #expect(pipeline.phase == .paused)

        await pipeline.resume(settings: chosen)
        #expect(pipeline.phase == .listening)
        #expect(pipeline.activeEngineKind == .whisperKit)
        #expect(!pipeline.isCoveringForCloud)
    }

    @Test("starting the phone's model while it gets ready behind the built-in waits for it instead of preparing it twice", .timeLimit(.minutes(1)))
    func restartWaitsForTheBackgroundPreparation() async throws {
        let network = FakeNetworkMonitor(.cellular)
        let model = waitingModel()
        let gate = PrepareGate()
        model.prepareGate = gate
        let pipeline = makePipeline(network: network, model: model, builtIn: FakeEngine(kind: .appleSpeech))
        await onBuiltIn(pipeline)
        network.change(to: .wifi)
        #expect(await eventually { model.heldPrepares == 1 })

        let restarting = Task { await pipeline.restart(settings: chosen) }
        try await Task.sleep(for: .milliseconds(150))
        #expect(model.heldPrepares == 1, "the same model was prepared twice at once")

        await gate.open()
        await restarting.value
        #expect(await eventually { pipeline.activeEngineKind == .whisperKit && pipeline.phase == .listening })
    }
}
