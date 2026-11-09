import Testing
@testable import OzenKit
import Foundation

private let pushed = [Float](repeating: 0.1, count: 1_024)

@MainActor
private func makeSoundsPipeline(
    built: BuiltEngines = BuiltEngines(),
    detector: FakeSoundDetector = FakeSoundDetector(),
    audio: FakeAudioCapturer = FakeAudioCapturer(),
    now: @escaping @Sendable () -> TimeInterval = { Date().timeIntervalSince1970 }
) -> CaptionPipeline {
    CaptionPipeline(
        audio: audio,
        engineFactory: { settings in
            let engine = FakeEngine(kind: settings.engine)
            built.add(engine)
            return engine
        },
        embedder: FakeEmbedder(),
        soundDetector: detector,
        recovery: .disabled,
        now: now
    )
}

@Suite("Sound alerts only")
@MainActor
struct SoundsOnlyTests {
    @Test("from listening: the engine is let go, sounds keep running and a smoke alarm still raises the alert")
    func fromListening() async {
        let built = BuiltEngines()
        let detector = FakeSoundDetector()
        let audio = FakeAudioCapturer()
        let pipeline = makeSoundsPipeline(built: built, detector: detector, audio: audio)
        await pipeline.start(settings: .default)
        #expect(built.aliveCount == 1)

        await pipeline.listenForSoundsOnly()
        #expect(await eventually { built.aliveCount == 0 })
        #expect(pipeline.isListeningForSoundsOnly)
        #expect(pipeline.stats.soundDetectionRunning)
        #expect(!pipeline.phase.isListening)

        audio.push(pushed)
        #expect(await eventually { detector.chunksSeen == 1 })
        detector.push(SoundObservation(identifier: "smoke_detector", confidence: 0.9, timestamp: 100))
        #expect(await eventually { pipeline.soundAlerts.count == 1 })
        #expect(pipeline.soundAlerts.first?.event.identifier == "smoke_detector")
    }

    @Test("from paused and from idle likewise")
    func fromPausedAndIdle() async {
        for pauseFirst in [true, false] {
            let built = BuiltEngines()
            let detector = FakeSoundDetector()
            let pipeline = makeSoundsPipeline(built: built, detector: detector)
            await pipeline.start(settings: .default)
            if pauseFirst { pipeline.pause(releasingAudio: true) } else { pipeline.stop() }

            await pipeline.listenForSoundsOnly()
            #expect(await eventually { built.aliveCount == 0 }, Comment(rawValue: "paused \(pauseFirst)"))
            #expect(pipeline.isListeningForSoundsOnly)
            detector.push(SoundObservation(identifier: "smoke_detector", confidence: 0.9, timestamp: 100))
            #expect(await eventually { pipeline.soundAlerts.count == 1 }, Comment(rawValue: "paused \(pauseFirst)"))
        }
    }

    @Test("during a call nothing starts, and the sounds come back when the call ends")
    func duringACall() async {
        let pipeline = makeSoundsPipeline()
        await pipeline.start(settings: .default)
        pipeline.systemInterruptionChanged(active: true)
        await pipeline.listenForSoundsOnly()
        #expect(!pipeline.isListeningForSoundsOnly)
        #expect(pipeline.phase.isListening)

        pipeline.systemInterruptionChanged(active: false)
        await pipeline.listenForSoundsOnly()
        #expect(pipeline.isListeningForSoundsOnly)

        pipeline.systemInterruptionChanged(active: true)
        #expect(!pipeline.isListeningForSoundsOnly)
        pipeline.systemInterruptionChanged(active: false)
        #expect(await eventually { pipeline.isListeningForSoundsOnly })
    }

    @Test("starting or resuming afterwards brings captions back with the sounds stopped first")
    func captionsComeBack() async {
        let built = BuiltEngines()
        let pipeline = makeSoundsPipeline(built: built)
        await pipeline.start(settings: .default)
        await pipeline.listenForSoundsOnly()
        await pipeline.resume(settings: .default)
        #expect(pipeline.phase.isListening)
        #expect(!pipeline.isListeningForSoundsOnly)
        #expect(built.aliveCount == 1)

        pipeline.stop()
        await pipeline.listenForSoundsOnly()
        await pipeline.start(settings: .default)
        #expect(pipeline.phase.isListening)
        #expect(!pipeline.isListeningForSoundsOnly)
    }

    @Test("stopping ends the sounds too")
    func stopEndsSounds() async {
        let pipeline = makeSoundsPipeline()
        await pipeline.start(settings: .default)
        await pipeline.listenForSoundsOnly()
        pipeline.stop()
        #expect(!pipeline.isListeningForSoundsOnly)
        #expect(!pipeline.stats.soundDetectionRunning)
    }

    @Test("a memory warning leaves the sounds running")
    func memoryWarning() async {
        let pipeline = makeSoundsPipeline()
        await pipeline.start(settings: .default)
        await pipeline.listenForSoundsOnly()
        pipeline.handleMemoryWarning(footprintBytes: 300_000_000)
        #expect(pipeline.isListeningForSoundsOnly)
        #expect(pipeline.stats.soundDetectionRunning)
    }

    @Test("with sound alerts off, nothing changes")
    func alertsOff() async {
        let pipeline = makeSoundsPipeline()
        await pipeline.start(settings: .default)
        pipeline.setSoundAlertPreferences(SoundAlertPreferences(isEnabled: false))
        await pipeline.listenForSoundsOnly()
        #expect(pipeline.phase.isListening)
        #expect(!pipeline.isListeningForSoundsOnly)
    }

    @Test("a microphone that can't be had leaves sounds-only off, not chosen with nothing listening")
    func failedStartIsNotChosen() async {
        for failure in ["permission", "session", "capture"] {
            let audio = FakeAudioCapturer()
            audio.requiresPreparedSession = true
            switch failure {
            case "permission": audio.permissionAnswer = .denied
            case "session": audio.prepareError = NSError(domain: "AVAudioSession", code: 561_017_449)
            default: audio.startError = NSError(domain: "FakeAudio", code: 3)
            }
            let pipeline = makeSoundsPipeline(audio: audio)
            await pipeline.listenForSoundsOnly()
            #expect(!pipeline.isListeningForSoundsOnly, Comment(rawValue: failure))
            #expect(!pipeline.soundsOnlyChosen, Comment(rawValue: failure))
            #expect(!BatteryAdvisor.watches(pipeline.phase, soundsOnly: pipeline.soundsOnlyChosen), Comment(rawValue: failure))
        }
    }

    @Test("a session set up for sounds-only after it was given up with Stop is handed back")
    func stoppedDuringSetupReleasesTheSession() async {
        let audio = FakeAudioCapturer()
        audio.requiresPreparedSession = true
        audio.holdPrepare = true
        let pipeline = makeSoundsPipeline(audio: audio)
        let starting = Task { await pipeline.listenForSoundsOnly() }
        #expect(await eventually { audio.isHoldingPrepare })
        pipeline.stop()
        let releasedBefore = audio.endSessionCalls
        audio.releasePrepare()
        await starting.value
        #expect(!pipeline.isListeningForSoundsOnly)
        #expect(audio.endSessionCalls == releasedBefore + 1)
    }

    @Test("a microphone lost during sounds-only is started again, and a second loss soon after ends it", .timeLimit(.minutes(1)))
    func captureLostRestartsOnce() async {
        for fromCaptions in [false, true] {
            let clock = TestClock()
            let audio = FakeAudioCapturer()
            let pipeline = makeSoundsPipeline(audio: audio, now: { clock.now })
            if fromCaptions { await pipeline.start(settings: .default) }
            await pipeline.listenForSoundsOnly()
            #expect(pipeline.isListeningForSoundsOnly, Comment(rawValue: "from captions \(fromCaptions)"))
            let starts = audio.calls.filter { $0 == "startCapture" }.count

            audio.onCaptureLost?()
            #expect(pipeline.stats.audioStalls == 1, Comment(rawValue: "from captions \(fromCaptions)"))
            #expect(await eventually { audio.calls.filter { $0 == "startCapture" }.count == starts + 1 && pipeline.isListeningForSoundsOnly },
                    Comment(rawValue: "from captions \(fromCaptions)"))
            #expect(pipeline.stats.soundDetectionRunning)

            clock.advance(5)
            audio.onCaptureLost?()
            try? await Task.sleep(for: .milliseconds(100))
            #expect(!pipeline.isListeningForSoundsOnly, Comment(rawValue: "from captions \(fromCaptions)"))
            #expect(!pipeline.soundsOnlyChosen, Comment(rawValue: "from captions \(fromCaptions)"))
            #expect(!pipeline.stats.soundDetectionRunning)
            #expect(audio.calls.filter { $0 == "startCapture" }.count == starts + 1)
        }
    }

    @Test("sounds-only counts as listening for the battery watch")
    func batteryWatch() {
        #expect(BatteryAdvisor.watches(.paused, soundsOnly: true))
        #expect(BatteryAdvisor.watches(.idle, soundsOnly: true))
        #expect(!BatteryAdvisor.watches(.paused))
    }
}

private final class MemoryStore: ListeningRecordStore, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: String?
    var value: String? { lock.withLock { stored } }
    func read() -> String? { value }
    func write(_ value: String) { lock.withLock { stored = value } }
    func clear() { lock.withLock { stored = nil } }
}

private final class DirectExecutor: ListeningExecutor {
    func async(_ job: @escaping @Sendable () -> Void) { job() }
    func sync(_ job: @Sendable () -> Void) { job() }
}

@Suite("Listening record while sounds-only")
struct SoundsOnlyRecordTests {
    @Test("sounds-only keeps the record alive through paused and idle, and stopping by hand clears it")
    func keepsRecord() {
        let store = MemoryStore()
        let recorder = ListeningRecorder(store: store, version: 1, now: { 1_000 }, executor: DirectExecutor())
        recorder.phaseChanged(.listening, recovering: false)
        recorder.phaseChanged(.paused, recovering: false, soundsOnly: true)
        #expect(store.value != nil)
        recorder.keepAlive()
        #expect(store.value != nil)
        recorder.phaseChanged(.idle, recovering: false, soundsOnly: true)
        #expect(store.value != nil)
        recorder.phaseChanged(.idle, recovering: false)
        #expect(store.value == nil)
    }

    @Test("sounds-only started from idle begins a record")
    func beginsFromIdle() {
        let store = MemoryStore()
        let recorder = ListeningRecorder(store: store, version: 1, now: { 1_000 }, executor: DirectExecutor())
        recorder.phaseChanged(.idle, recovering: false, soundsOnly: true)
        #expect(recorder.isRecording)
    }
}
