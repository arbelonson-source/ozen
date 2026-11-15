import Foundation
import Testing
@testable import OzenKit

@Suite("StoppedCaptionsNotice")
struct StoppedCaptionsNoticeTests {
    private let glitch = PipelineFailure(kind: .transcriptionStopped, detail: "stream ended")

    private func engineFailure(_ kind: EngineUnavailability.Kind) -> PipelineFailure {
        PipelineFailure(kind: .engineUnavailable, detail: "", engineUnavailability: EngineUnavailability(kind: kind, detail: ""))
    }

    private func cause(
        _ phase: PipelinePhase,
        retryScheduled: Bool = false,
        interrupted: Bool = false,
        byCall: Bool = false,
        callEnded: Bool = false,
        soundsOnly: Bool = false,
        soundAlertsNotWorking: Bool = false
    ) -> StoppedCaptionsNotice.Cause? {
        StoppedCaptionsNotice.cause(
            phase: phase,
            retryScheduled: retryScheduled,
            systemInterrupted: interrupted,
            interruptedByCall: byCall,
            callEndedDuringInterruption: callEnded,
            soundsOnlyChosen: soundsOnly,
            soundAlertsNotWorking: soundAlertsNotWorking
        )
    }

    @Test("a failure nothing will retry is a stop; one with a retry lined up is not")
    func failureWithoutRetry() {
        #expect(cause(.failed(glitch)) == .failed(glitch))
        #expect(cause(.failed(glitch), retryScheduled: true) == nil)
    }

    @Test("running, starting, paused or stopped on purpose is not a stop")
    func notStopped() {
        #expect(cause(.listening) == nil)
        #expect(cause(.startingAudio) == nil)
        #expect(cause(.paused) == nil)
        #expect(cause(.idle) == nil)
    }

    @Test("waiting for Wi-Fi starts by itself, so it is not a stop")
    func waitingForWiFi() {
        #expect(cause(.failed(engineFailure(.waitingForWiFi))) == nil)
        #expect(cause(.failed(engineFailure(.notEnoughStorage))) == .failed(engineFailure(.notEnoughStorage)))
    }

    @Test("during a call nothing is a stop; after it, a microphone that never came back is")
    func calls() {
        #expect(cause(.listening, interrupted: true, byCall: true) == nil)
        #expect(cause(.failed(glitch), interrupted: true, byCall: true) == nil)
        #expect(cause(.listening, interrupted: true, byCall: true, callEnded: true) == .callEnded)
        #expect(cause(.failed(glitch), interrupted: true, byCall: true, callEnded: true) == .callEnded)
        // Paused before the call: nothing was running to stop.
        #expect(cause(.paused, interrupted: true, byCall: true, callEnded: true) == nil)
    }

    @Test("another app keeping the microphone is a stop to tell about, unless a call is why or captions were paused")
    func otherAppHasMicrophone() {
        #expect(cause(.listening, interrupted: true) == .otherAppHasMicrophone)
        #expect(cause(.failed(glitch), interrupted: true) == .otherAppHasMicrophone)
        #expect(cause(.paused, interrupted: true) == nil)
        #expect(cause(.idle, interrupted: true) == nil)
        #expect(cause(.listening, interrupted: true, byCall: true) == nil)
        #expect(cause(.listening, interrupted: false) == nil)
    }

    @Test("sound alerts alone, which leave the phase idle or paused, are told about when another app or a stuck call has the microphone")
    func soundsOnlyInterrupted() {
        #expect(cause(.idle, interrupted: true, soundsOnly: true) == .otherAppHasMicrophone)
        #expect(cause(.paused, interrupted: true, soundsOnly: true) == .otherAppHasMicrophone)
        #expect(cause(.idle, interrupted: true, byCall: true, soundsOnly: true) == nil)
        #expect(cause(.idle, interrupted: true, byCall: true, callEnded: true, soundsOnly: true) == .callEnded)
        #expect(cause(.idle, interrupted: true) == nil)
    }

    @Test("the other-app notice goes out after a minute, once, and is cancelled when the app opens or the microphone is back")
    func otherAppScheduled() {
        var notice = StoppedCaptionsNotice()
        #expect(StoppedCaptionsNotice.otherAppDelay == 60)
        #expect(notice.update(for: .otherAppHasMicrophone, appIsActive: false, isEnabled: false) == nil)

        guard case .schedule(let content, let delay) = notice.update(for: .otherAppHasMicrophone, appIsActive: false, isEnabled: true) else {
            Issue.record("expected a notification to schedule")
            return
        }
        #expect(delay == 60)
        #expect(content.identifier == StoppedCaptionsNotice.identifier)
        #expect(content.isUrgent)
        #expect(notice.update(for: .otherAppHasMicrophone, appIsActive: false, isEnabled: true) == nil)

        #expect(notice.update(for: .otherAppHasMicrophone, appIsActive: true, isEnabled: true) == .cancelScheduled(identifier: StoppedCaptionsNotice.identifier))
        #expect(notice.update(for: .otherAppHasMicrophone, appIsActive: false, isEnabled: true) == nil)

        #expect(notice.update(for: nil, appIsActive: false, isEnabled: true) == nil)
        guard case .schedule = notice.update(for: .otherAppHasMicrophone, appIsActive: false, isEnabled: true) else {
            Issue.record("a new interruption should be told again")
            return
        }
        #expect(notice.update(for: nil, appIsActive: false, isEnabled: true) == .cancelScheduled(identifier: StoppedCaptionsNotice.identifier))
    }

    @Test("a scheduled sound-alerts notice is dropped and the failure is posted at once with its own wording")
    func scheduledThenFailed() {
        var notice = StoppedCaptionsNotice()
        let id = StoppedCaptionsNotice.identifier
        guard case .schedule = notice.update(for: .soundAlertsNotWorking, appIsActive: false, isEnabled: true) else {
            Issue.record("expected a schedule")
            return
        }
        let failed = StoppedCaptionsNotice.Cause.failed(glitch)
        guard case .replace(let old, .post(let content))? = notice.update(for: failed, appIsActive: false, isEnabled: true) else {
            Issue.record("expected a cancel plus a post")
            return
        }
        #expect(old == .cancelScheduled(identifier: id))
        #expect(content.title == "הכתוביות נעצרו")
        #expect(notice.update(for: .failed(engineFailure(.noInternet)), appIsActive: false, isEnabled: true) == nil)
        #expect(notice.update(for: nil, appIsActive: false, isEnabled: true) == .withdraw(identifier: id))
    }

    @Test("a scheduled other-app notice is dropped and sound alerts are scheduled in its place")
    func otherAppThenSoundAlerts() {
        var notice = StoppedCaptionsNotice()
        let id = StoppedCaptionsNotice.identifier
        _ = notice.update(for: .otherAppHasMicrophone, appIsActive: false, isEnabled: true)
        guard case .replace(let old, .schedule(let content, let delay))? = notice.update(for: .soundAlertsNotWorking, appIsActive: false, isEnabled: true) else {
            Issue.record("expected a cancel plus a schedule")
            return
        }
        #expect(old == .cancelScheduled(identifier: id))
        #expect(delay == StoppedCaptionsNotice.soundAlertsDelay)
        #expect(content.title == StoppedCaptionsNotice.soundAlertsNotWorkingTitle)
        #expect(notice.update(for: .soundAlertsNotWorking, appIsActive: false, isEnabled: true) == nil)
    }

    @Test("a posted failure is withdrawn when the cause becomes another app, which is then scheduled")
    func failedThenOtherApp() {
        var notice = StoppedCaptionsNotice()
        let id = StoppedCaptionsNotice.identifier
        _ = notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true)
        guard case .replace(let old, .schedule(_, let delay))? = notice.update(for: .otherAppHasMicrophone, appIsActive: false, isEnabled: true) else {
            Issue.record("expected a withdraw plus a schedule")
            return
        }
        #expect(old == .withdraw(identifier: id))
        #expect(delay == StoppedCaptionsNotice.otherAppDelay)
    }

    @Test("a new cause seen while the app is open is told once the phone is put away")
    func changedWhileOpen() {
        var notice = StoppedCaptionsNotice()
        _ = notice.update(for: .soundAlertsNotWorking, appIsActive: false, isEnabled: true)
        #expect(notice.update(for: .soundAlertsNotWorking, appIsActive: true, isEnabled: true) == .cancelScheduled(identifier: StoppedCaptionsNotice.identifier))
        #expect(notice.update(for: .soundAlertsNotWorking, appIsActive: false, isEnabled: true) == nil)
        #expect(notice.update(for: .failed(glitch), appIsActive: true, isEnabled: true) == nil)
        guard case .post? = notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true) else {
            Issue.record("the worse cause should be posted")
            return
        }
    }

    @Test("the other-app notice says another app is using the microphone and what to do")
    func otherAppWording() {
        let body = StoppedCaptionsNotice.content(for: .otherAppHasMicrophone).body
        #expect(body.contains("אפליקציה אחרת"))
        #expect(!body.contains("שיחת הטלפון"))
        Localization.$override.withValue(.english) {
            let content = StoppedCaptionsNotice.content(for: .otherAppHasMicrophone)
            #expect(content.title == "Captions paused")
            #expect(content.body.contains("Another app"))
            #expect(content.body.contains("sound alerts"))
            #expect(content.body.contains("Open Ozen"))
            #expect(!content.body.contains("After the call"))
        }
    }

    @Test("posted once while the phone is put away, never while the app is on screen or switched off")
    func postsOnce() {
        var notice = StoppedCaptionsNotice()
        #expect(notice.update(for: .failed(glitch), appIsActive: true, isEnabled: true) == nil)
        #expect(notice.update(for: .failed(glitch), appIsActive: false, isEnabled: false) == nil)

        guard case .post(let content) = notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true) else {
            Issue.record("expected a notification")
            return
        }
        #expect(content.identifier == StoppedCaptionsNotice.identifier)
        #expect(content.title == "הכתוביות נעצרו")
        #expect(notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true) == nil)
        #expect(notice.update(for: .callEnded, appIsActive: false, isEnabled: true) == nil)
    }

    @Test("once captions run again the notice is withdrawn, and a later stop is told again")
    func withdrawsAndRearms() {
        var notice = StoppedCaptionsNotice()
        #expect(notice.update(for: nil, appIsActive: false, isEnabled: true) == nil)
        _ = notice.update(for: .callEnded, appIsActive: false, isEnabled: true)

        #expect(notice.update(for: nil, appIsActive: false, isEnabled: true) == .withdraw(identifier: StoppedCaptionsNotice.identifier))
        #expect(notice.update(for: nil, appIsActive: false, isEnabled: true) == nil)

        if case .post = notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true) {} else {
            Issue.record("a new stop after recovering should notify again")
        }
    }

    @Test("opening the app while captions are still stopped takes the notice away, without posting it again later")
    func withdrawnOnScreen() {
        var notice = StoppedCaptionsNotice()
        _ = notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true)
        #expect(notice.update(for: .failed(glitch), appIsActive: true, isEnabled: true) == .withdraw(identifier: StoppedCaptionsNotice.identifier))
        #expect(notice.update(for: .failed(glitch), appIsActive: true, isEnabled: true) == nil)
        #expect(notice.update(for: .failed(glitch), appIsActive: false, isEnabled: true) == nil)

        // Captions ran again, then stopped again: that's news.
        #expect(notice.update(for: nil, appIsActive: true, isEnabled: true) == nil)
        if case .post = notice.update(for: .callEnded, appIsActive: false, isEnabled: true) {} else {
            Issue.record("a new stop after recovering should notify again")
        }
    }

    @Test("the notice breaks through Focus: captions stopping in a pocket is something to know now")
    func urgent() {
        #expect(StoppedCaptionsNotice.content(for: .callEnded).isUrgent)
        #expect(StoppedCaptionsNotice.content(for: .failed(glitch)).isUrgent)
        #expect(StoppedCaptionsNotice.content(for: .failed(glitch)).threadIdentifier == "status")
    }

    @Test("a plain ws:// address out on the internet points to the wss:// one, not to a computer that may well be on")
    func plainAddressNotice() {
        let plain = PipelineFailure(
            kind: .engineUnavailable, detail: "",
            engineUnavailability: EngineUnavailability(kind: .homeServerUnreachable, detail: HomeServer.needsEncryption)
        )
        let body = StoppedCaptionsNotice.content(for: .failed(plain)).body
        #expect(body.contains("wss://"))
        #expect(!body.contains("אין תשובה מהמחשב"))
    }

    @Test("the message says what she can do about it")
    func wording() {
        let callEnded = StoppedCaptionsNotice.content(for: .callEnded).body
        let permission = StoppedCaptionsNotice.content(for: .failed(PipelineFailure(kind: .microphonePermissionDenied, detail: ""))).body
        let speechPermission = StoppedCaptionsNotice.content(for: .failed(engineFailure(.permissionDenied))).body
        let storage = StoppedCaptionsNotice.content(for: .failed(engineFailure(.notEnoughStorage))).body
        let noMic = StoppedCaptionsNotice.content(for: .failed(PipelineFailure(kind: .noAudioInputs, detail: ""))).body
        let other = StoppedCaptionsNotice.content(for: .failed(glitch)).body
        let cloudKey = StoppedCaptionsNotice.content(for: .failed(engineFailure(.cloudKeyNeeded))).body
        let cloudCredit = StoppedCaptionsNotice.content(for: .failed(engineFailure(.cloudOutOfCredit))).body
        let offline = StoppedCaptionsNotice.content(for: .failed(engineFailure(.noInternet))).body
        let computer = StoppedCaptionsNotice.content(for: .failed(engineFailure(.homeServerUnreachable))).body

        #expect(callEnded.contains("אחרי שיחת הטלפון"))
        #expect(permission == speechPermission)
        #expect(permission.contains("הרשאה"))
        #expect(storage.contains("מקום"))
        #expect(noMic.contains("מיקרופון"))
        // Running out of credit is not a key to check: whoever helps her
        // tops up the account instead.
        #expect(cloudKey != cloudCredit)
        #expect(cloudCredit.contains("הקרדיט") && !cloudCredit.contains("מפתח"))
        #expect(offline.contains("אינטרנט"))
        #expect(offline.contains("זיהוי הדיבור שבטלפון") && !offline.contains("Whisper"))
        // Opening the app doesn't wake the computer; captions come back by
        // themselves once it answers.
        #expect(computer.contains("אין תשובה מהמחשב"))
        #expect(Set([callEnded, permission, storage, noMic, other, cloudKey, cloudCredit, offline, computer, StoppedCaptionsNotice.content(for: .otherAppHasMicrophone).body]).count == 10)
    }

    @Test("the message is in English when the app is")
    func englishWording() {
        Localization.$override.withValue(.english) {
            let content = StoppedCaptionsNotice.content(for: .callEnded)
            #expect(content.title == "Captions stopped")
            #expect(content.body.contains("After the call"))
            let permission = StoppedCaptionsNotice.content(for: .failed(PipelineFailure(kind: .microphonePermissionDenied, detail: ""))).body
            #expect(permission.contains("permission"))
            let computer = StoppedCaptionsNotice.content(for: .failed(engineFailure(.homeServerUnreachable))).body
            #expect(computer.hasPrefix("No answer from the computer"))
        }
    }

    @Test("sound alerts that can't work are a cause while captions run, are retried or wait, and not otherwise")
    func soundAlertsNotWorkingCause() {
        #expect(cause(.listening, soundAlertsNotWorking: true) == .soundAlertsNotWorking)
        #expect(cause(.idle, soundsOnly: true, soundAlertsNotWorking: true) == .soundAlertsNotWorking)
        #expect(cause(.failed(glitch), retryScheduled: true, soundAlertsNotWorking: true) == .soundAlertsNotWorking)
        #expect(cause(.failed(engineFailure(.waitingForWiFi)), soundAlertsNotWorking: true) == .soundAlertsNotWorking)
        #expect(cause(.failed(glitch), soundAlertsNotWorking: true) == .failed(glitch))
        #expect(cause(.listening, interrupted: true, byCall: true, soundAlertsNotWorking: true) == nil)
        #expect(cause(.listening) == nil)
    }

    @Test("the not-working notice goes out after a minute of it not working, once, and is cancelled when sound alerts work again or the app opens")
    func soundAlertsNotWorkingNotice() {
        var notice = StoppedCaptionsNotice()
        #expect(StoppedCaptionsNotice.soundAlertsDelay == 60)
        #expect(notice.update(for: .soundAlertsNotWorking, appIsActive: true, isEnabled: true) == nil)
        #expect(notice.update(for: .soundAlertsNotWorking, appIsActive: false, isEnabled: false) == nil)
        guard case .schedule(let content, let delay) = notice.update(for: .soundAlertsNotWorking, appIsActive: false, isEnabled: true) else {
            Issue.record("expected a notification to schedule")
            return
        }
        #expect(delay == 60)
        #expect(content.identifier == StoppedCaptionsNotice.identifier)
        #expect(content.isUrgent)
        #expect(notice.update(for: .soundAlertsNotWorking, appIsActive: false, isEnabled: true) == nil)
        #expect(notice.update(for: nil, appIsActive: false, isEnabled: true) == .cancelScheduled(identifier: StoppedCaptionsNotice.identifier))
        guard case .schedule = notice.update(for: .soundAlertsNotWorking, appIsActive: false, isEnabled: true) else {
            Issue.record("a new stop should be told again")
            return
        }
        #expect(notice.update(for: .soundAlertsNotWorking, appIsActive: true, isEnabled: true) == .cancelScheduled(identifier: StoppedCaptionsNotice.identifier))
    }

    private func scheduledDelay(_ update: StoppedCaptionsNotice.Update?) -> TimeInterval? {
        guard case .schedule(_, let delay)? = update else { return nil }
        return delay
    }

    @Test("sound alerts that fail 50 seconds, work 20, and fail again are still told: the minute counts across the gaps")
    func soundAlertsFlappingIsStillTold() {
        var notice = StoppedCaptionsNotice()
        let down = StoppedCaptionsNotice.Cause.soundAlertsNotWorking
        #expect(scheduledDelay(notice.update(for: down, appIsActive: false, isEnabled: true, now: 0)) == 60)
        #expect(notice.update(for: nil, appIsActive: false, isEnabled: true, now: 50) == .cancelScheduled(identifier: StoppedCaptionsNotice.identifier))
        #expect(scheduledDelay(notice.update(for: down, appIsActive: false, isEnabled: true, now: 70)) == 10)
        _ = notice.update(for: nil, appIsActive: false, isEnabled: true, now: 75)
        #expect(scheduledDelay(notice.update(for: down, appIsActive: false, isEnabled: true, now: 95)) == StoppedCaptionsNotice.soundAlertsMinimumDelay)
    }

    @Test("one short blip of sound alerts failing does not bring the notice forward, and a long gap forgets old failures")
    func soundAlertsBlipAndWindow() {
        var notice = StoppedCaptionsNotice()
        let down = StoppedCaptionsNotice.Cause.soundAlertsNotWorking
        _ = notice.update(for: down, appIsActive: false, isEnabled: true, now: 0)
        _ = notice.update(for: nil, appIsActive: false, isEnabled: true, now: 5)
        #expect(scheduledDelay(notice.update(for: down, appIsActive: false, isEnabled: true, now: 30)) == 55)
        _ = notice.update(for: nil, appIsActive: false, isEnabled: true, now: 35)
        let later = 35 + StoppedCaptionsNotice.soundAlertsWindow + 1
        #expect(scheduledDelay(notice.update(for: down, appIsActive: false, isEnabled: true, now: later)) == 60)
    }

    @Test("the not-working notice says sounds may be missed and that Ozen keeps trying")
    func soundAlertsNotWorkingWording() {
        Localization.$override.withValue(.english) {
            let content = StoppedCaptionsNotice.content(for: .soundAlertsNotWorking)
            #expect(content.title == "Sound alerts aren’t working")
            #expect(content.body.contains("keeps trying"))
            #expect(content.body.contains("doorbell"))
        }
        let hebrew = Localization.$override.withValue(.hebrew) { StoppedCaptionsNotice.content(for: .soundAlertsNotWorking) }
        #expect(hebrew.title == "ההתראות על צלילים לא פועלות")
        #expect(hebrew.body.contains("ממשיכה לנסות"))
    }

}

@Suite("CaptionPipeline phase change callback")
@MainActor
struct CaptionPipelinePhaseChangeTests {
    @Test("each step is reported once, not every bit of download progress")
    func reportsSteps() async {
        let engine = FakeEngine(progressUpdates: [
            EnginePreparationProgress(stage: .downloadingModel, fraction: 0.1),
            EnginePreparationProgress(stage: .downloadingModel, fraction: 0.5),
            EnginePreparationProgress(stage: .downloadingModel, fraction: 0.9),
        ])
        let pipeline = CaptionPipeline(
            audio: FakeAudioCapturer(),
            engineFactory: { _ in engine },
            embedder: FakeEmbedder(),
            recovery: .disabled,
            audioWatchdog: .disabled
        )
        var reported: [String] = []
        pipeline.onPhaseChange = { phase in
            switch phase {
            case .idle: reported.append("idle")
            case .requestingMicrophonePermission: reported.append("permission")
            case .preparingEngine: reported.append("preparing")
            case .startingAudio: reported.append("audio")
            case .listening: reported.append("listening")
            case .paused: reported.append("paused")
            case .failed: reported.append("failed")
            }
        }

        await pipeline.start(settings: .default)
        try? await Task.sleep(for: .milliseconds(100))
        pipeline.pause()

        #expect(reported == ["permission", "preparing", "audio", "listening", "paused"])
    }
}
