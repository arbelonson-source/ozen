import Foundation
import Testing
@testable import OzenKit

@MainActor
@Suite("Cloud captions reach the home computer sooner")
struct CloudCoverHomeSoonerTests {
    private func settings(paired: Bool = true) -> AppSettings {
        var settings = AppSettings.default
        settings.engine = .cloud
        if paired { settings.homeServerAddress = "wss://192.168.1.20:8765" }
        return settings
    }

    private func loadFailure() -> EngineAvailability {
        .unavailable(.modelLoadFailed, "the model would not load")
    }

    private func pipeline(cloud: any TranscriptionEngine, phone: FakeEngine, home: FakeEngine, recovery: AutoRecoveryPolicy = .disabled, clock: TestClock = TestClock(), newHome: FakeEngine? = nil) -> (CaptionPipeline, FakeAudioCapturer) {
        let audio = FakeAudioCapturer()
        let captions = CaptionPipeline(
            audio: audio,
            engineFactory: {
                switch $0.engine {
                case .cloud: cloud
                case .homeServer: $0.homeServerAddress.hasSuffix(":8766") ? (newHome ?? home) : home
                default: phone
                }
            },
            embedder: FakeEmbedder(),
            recovery: recovery,
            now: { clock.now }
        )
        captions.cloudRecheckSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 0
        return (captions, audio)
    }

    private func homeAnswering(_ answers: Bool = true) -> FakeEngine {
        FakeEngine(kind: .homeServer, availability: answers ? .available : .unavailable(.homeServerUnreachable, "no answer"))
    }

    @Test("an installed phone model that fails to load hands over to the home computer, and the cloud comes back later")
    func phoneModelFailsToLoad() async {
        let cloud = FakeEngine(kind: .cloud)
        let phone = FakeEngine(kind: .whisperKit, availability: loadFailure())
        let home = homeAnswering()
        let (captions, _) = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .homeServer })
        #expect(captions.isCoveringForCloud)
        #expect(phone.prepareCount == 1)
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        #expect(!captions.isCoveringForCloud)
    }

    @Test("a phone model that fails to load and a home computer that does not answer stay as before")
    func phoneModelFailsAndHomeSilent() async {
        let cloud = FakeEngine(kind: .cloud)
        let phone = FakeEngine(kind: .whisperKit, availability: loadFailure())
        let home = homeAnswering(false)
        let (captions, _) = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase.failure?.engineUnavailability?.kind == .modelLoadFailed })
        #expect(captions.activeEngineKind != .homeServer)
    }

    @Test("a phone model that fails to load with no home address stays as before")
    func phoneModelFailsNotPaired() async {
        let cloud = FakeEngine(kind: .cloud)
        let phone = FakeEngine(kind: .whisperKit, availability: loadFailure())
        let home = homeAnswering()
        let (captions, _) = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings(paired: false))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase.failure?.engineUnavailability?.kind == .modelLoadFailed })
        #expect(home.prepareCount == 0)
    }

    private func hangingRig(homeAnswers: Bool = true, clock: TestClock = TestClock(), newHome: FakeEngine? = nil) -> (CaptionPipeline, FakeAudioCapturer, FakeCloudHTTP, FakeEngine) {
        let http = FakeCloudHTTP(answers: [.text("hello")])
        let cloud = CloudSpeechEngine(http: http, failedSegmentPauseSeconds: 0.001, approvalSeconds: 0.01, apiKey: { "sk-test" })
        let phone = FakeEngine(kind: .whisperKit)
        phone.pendingDownload = 400
        let home = homeAnswering(homeAnswers)
        let (captions, audio) = pipeline(cloud: cloud, phone: phone, home: home, clock: clock, newHome: newHome)
        return (captions, audio, http, home)
    }

    private func talk(_ audio: FakeAudioCapturer, steps: Int, until done: () -> Bool) async {
        for _ in 0..<steps where !done() {
            for _ in 0..<21 { audio.push((0..<1_600).map { $0 % 2 == 0 ? 0.2 : -0.2 }) }
            _ = await eventually(within: .milliseconds(300)) { done() }
        }
    }

    @Test("no answer at all with only the home computer to cover: it takes over after the first timeout")
    func timeoutHandsOverToHome() async {
        let (captions, audio, http, home) = hangingRig()
        await captions.start(settings: settings())
        http.blackHole = true
        await talk(audio, steps: 6) { captions.isCoveringForCloud }
        #expect(await eventually { captions.isCoveringForCloud && captions.phase.isListening && captions.activeEngineKind == .homeServer })
        #expect(http.transcriptionRequests.count == 1)
        #expect(home.prepareCount >= 1)
    }

    @Test("without a home address a timeout is retried as before and nothing is handed over")
    func timeoutWithoutHomeAddress() async {
        let (captions, audio, http, _) = hangingRig()
        await captions.start(settings: settings(paired: false))
        http.blackHole = true
        await talk(audio, steps: 3) { http.transcriptionRequests.count >= 2 }
        #expect(http.transcriptionRequests.count >= 2)
        #expect(!captions.isCoveringForCloud)
        #expect(captions.activeEngineKind == .cloud)
    }

    @Test("the home computer failing while it covers does not start a phone model that is not installed, and the cloud still comes back")
    func homeFailsWhileCovering() async {
        let cloud = FakeEngine(kind: .cloud)
        let phone = FakeEngine(kind: .whisperKit)
        phone.pendingDownload = 800
        let home = homeAnswering()
        let retries = AutoRecoveryPolicy(glitchDelays: [0.05, 0.05], downloadDelays: [], microphoneTailDelays: [])
        let (captions, _) = pipeline(cloud: cloud, phone: phone, home: home, recovery: retries)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.availability = .unavailable(.noInternet, "still down")
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .homeServer })
        home.endStream(throwing: EngineUnavailability(kind: .homeServerUnreachable, detail: "connection lost"))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .homeServer && home.prepareCount >= 2 })
        #expect(phone.prepareCount == 0)
        #expect(captions.isCoveringForCloud)
        cloud.availability = .available
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        #expect(phone.prepareCount == 0)
    }

    @Test("a cloud that is only briefly unavailable and has no phone model is covered by the home computer once the retries run out")
    func temporarilyUnavailableCoveredByHome() async {
        let cloud = FakeEngine(kind: .cloud)
        let phone = FakeEngine(kind: .whisperKit)
        phone.pendingDownload = 800
        let home = homeAnswering()
        let (captions, _) = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.endStream(throwing: EngineUnavailability(kind: .temporarilyUnavailable, detail: "the cloud service answered 503"))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .homeServer })
        #expect(phone.prepareCount == 0)
    }

    @Test("a pause and resume while the home computer covers goes on with the home computer, not a phone model that is not installed")
    func resumeKeepsTheHomeComputer() async {
        let cloud = FakeEngine(kind: .cloud)
        let phone = FakeEngine(kind: .whisperKit)
        phone.pendingDownload = 800
        let home = homeAnswering()
        let (captions, _) = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.availability = .unavailable(.noInternet, "still down")
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .homeServer })
        captions.pause()
        await captions.resume(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .homeServer })
        #expect(phone.prepareCount == 0)
        cloud.availability = .available
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
    }

    @Test("the home computer failing after covering for a phone model that would not load leaves the cloud as the one to go back to")
    func cloudStaysTheTargetAfterBothCoversFail() async {
        let cloud = FakeEngine(kind: .cloud)
        let phone = FakeEngine(kind: .whisperKit, availability: loadFailure())
        let home = homeAnswering()
        let (captions, _) = pipeline(cloud: cloud, phone: phone, home: home)
        await captions.start(settings: settings())
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        cloud.availability = .unavailable(.noInternet, "still down")
        cloud.endStream(throwing: EngineUnavailability(kind: .noInternet, detail: "connection lost"))
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .homeServer })
        home.endStream(throwing: EngineUnavailability(kind: .homeServerUnreachable, detail: "connection lost"))
        #expect(await eventually { captions.phase.failure?.engineUnavailability?.kind == .modelLoadFailed && phone.prepareCount >= 2 })
        cloud.availability = .available
        #expect(await eventually { captions.phase == .listening && captions.activeEngineKind == .cloud })
        #expect(!captions.isCoveringForCloud)
    }

    @Test("a saved home address that does not answer: a timeout is retried by the cloud as before, with no failure and no hand-over")
    func timeoutWithUnreachableHome() async {
        let (captions, audio, http, home) = hangingRig(homeAnswers: false)
        await captions.start(settings: settings())
        http.blackHole = true
        await talk(audio, steps: 15) { http.transcriptionRequests.count >= 2 }
        #expect(http.transcriptionRequests.count >= 2)
        #expect(!captions.isCoveringForCloud)
        #expect(captions.phase == .listening)
        #expect(captions.activeEngineKind == .cloud)
    }

    private final class Asked: @unchecked Sendable {
        var whileListening = 0
    }

    /// Counts the times a home computer is asked while captions have not
    /// failed: the cloud timeout's check. The stop after four failed
    /// requests asks again on its own, once captions have failed.
    @MainActor
    private func countTimeoutChecks(of engine: FakeEngine, in captions: CaptionPipeline, afterFirst: (@Sendable () -> Void)? = nil) -> Asked {
        let asked = Asked()
        engine.duringPrepare = { [weak captions] in
            if let captions, captions.phase.failure == nil {
                asked.whileListening += 1
                if asked.whileListening == 1 { afterFirst?() }
            }
        }
        return asked
    }

    @Test("an unreachable home computer is asked once for a minute, however often the cloud times out")
    func silentHomeIsAskedOnce() async {
        let (captions, audio, http, home) = hangingRig(homeAnswers: false)
        let asked = countTimeoutChecks(of: home, in: captions)
        await captions.start(settings: settings())
        http.blackHole = true
        await talk(audio, steps: 15) { http.transcriptionRequests.count >= 3 }
        #expect(http.transcriptionRequests.count >= 3)
        #expect(asked.whileListening == 1)
    }

    @Test("after a minute the unreachable home computer is asked again")
    func silentHomeIsAskedAgainAfterAMinute() async {
        let clock = TestClock()
        let (captions, audio, http, home) = hangingRig(homeAnswers: false, clock: clock)
        // The cloud answers again as soon as the first check is made, so no
        // run of failed requests can stop captions while the minute passes.
        let asked = countTimeoutChecks(of: home, in: captions) { http.blackHole = false }
        await captions.start(settings: settings())
        http.blackHole = true
        await talk(audio, steps: 15) { asked.whileListening >= 1 }
        #expect(asked.whileListening == 1)
        let answered = http.transcriptionRequests.count
        await talk(audio, steps: 15) { http.transcriptionRequests.count > answered }
        clock.advance(61)
        http.blackHole = true
        await talk(audio, steps: 15) { asked.whileListening >= 2 }
        #expect(asked.whileListening == 2)
    }

    @Test("a changed home address, applied the way the app does while captions run, is asked at once")
    func changedAddressIsAskedAtOnce() async {
        let newHome = homeAnswering(false)
        let (captions, audio, http, home) = hangingRig(homeAnswers: false, newHome: newHome)
        let asked = countTimeoutChecks(of: home, in: captions)
        let askedNew = countTimeoutChecks(of: newHome, in: captions)
        await captions.start(settings: settings())
        http.blackHole = true
        await talk(audio, steps: 15) { asked.whileListening >= 1 }
        #expect(asked.whileListening == 1)
        var other = settings()
        other.homeServerAddress = "wss://192.168.1.30:8766"
        http.blackHole = false
        await captions.restart(settings: other)
        http.blackHole = true
        await talk(audio, steps: 15) { askedNew.whileListening >= 1 }
        #expect(askedNew.whileListening == 1)
        #expect(asked.whileListening == 1)
    }
}
