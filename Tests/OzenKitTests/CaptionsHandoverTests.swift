import Testing
@testable import OzenKit

@Suite("Captions stepping aside for a recording")
struct CaptionsHandoverTests {
    private let failed = PipelinePhase.failed(PipelineFailure(kind: .transcriptionStopped, detail: ""))

    @Test("running captions are paused, and brought back when the screen is done")
    func listening() {
        var handover = CaptionsHandover()
        let pause = handover.begin(phase: .listening)
        #expect(pause)
        #expect(handover.pausedHere)
        let resume = handover.end(phase: .paused)
        #expect(resume)
        #expect(!handover.pausedHere)
    }

    @Test("captions that are paused, idle, failed or still starting are left alone both ways")
    func notRunning() {
        let starting = PipelinePhase.preparingEngine(EnginePreparationProgress(stage: .loadingModel))
        for phase in [PipelinePhase.paused, .idle, failed, starting, .startingAudio] {
            var handover = CaptionsHandover()
            let pause = handover.begin(phase: phase)
            #expect(!pause, "\(phase)")
            #expect(!handover.pausedHere)
            let resume = handover.end(phase: phase)
            #expect(!resume, "\(phase)")
        }
    }

    @Test("captions someone brought back in between are not started a second time")
    func manualResume() {
        var handover = CaptionsHandover()
        _ = handover.begin(phase: .listening)
        let resume = handover.end(phase: .listening)
        #expect(!resume)
        #expect(!handover.pausedHere)
    }

    @Test("captions someone stopped in between are not started either")
    func stoppedInBetween() {
        var handover = CaptionsHandover()
        _ = handover.begin(phase: .listening)
        let resume = handover.end(phase: .idle)
        #expect(!resume)
    }

    @Test("giving back twice, as done and then closing would, resumes only once")
    func onlyOnce() {
        var handover = CaptionsHandover()
        _ = handover.begin(phase: .listening)
        let first = handover.end(phase: .paused)
        let second = handover.end(phase: .paused)
        #expect(first)
        #expect(!second)
    }

    @Test("a second look at running captions does not pause them again")
    func beginTwice() {
        var handover = CaptionsHandover()
        let first = handover.begin(phase: .listening)
        let second = handover.begin(phase: .listening)
        let resume = handover.end(phase: .paused)
        #expect(first)
        #expect(!second)
        #expect(resume)
    }

    @Test("running captions with sound alerts on keep the sounds, off they just pause")
    func keepsSounds() {
        var on = CaptionsHandover()
        #expect(on.begin(phase: .listening, soundAlertsOn: true) == .keepSounds)
        #expect(on.pausedHere)
        var off = CaptionsHandover()
        #expect(off.begin(phase: .listening, soundAlertsOn: false) == .pause)
    }

    @Test("captions not running are left alone even with sound alerts on")
    func notRunningWithSounds() {
        for phase in [PipelinePhase.paused, .idle, failed] {
            var handover = CaptionsHandover()
            #expect(handover.begin(phase: phase, soundAlertsOn: true) == .leaveAlone, "\(phase)")
            #expect(!handover.pausedHere)
        }
    }

    @Test("captions come back after sounds-only, but not when the person stopped them meanwhile")
    func comingBack() {
        var handover = CaptionsHandover()
        _ = handover.begin(phase: .listening, soundAlertsOn: true)
        let back = handover.end(phase: .paused)
        #expect(back)
        var stopped = CaptionsHandover()
        _ = stopped.begin(phase: .listening, soundAlertsOn: true)
        let again = stopped.end(phase: .idle)
        #expect(!again)
    }

    @Test("sounds-only that did not take leaves captions running, so they are paused plainly")
    func fallback() {
        #expect(CaptionsHandover.needsPlainPause(after: .keepSounds, phase: .listening))
        #expect(!CaptionsHandover.needsPlainPause(after: .keepSounds, phase: .paused))
        #expect(!CaptionsHandover.needsPlainPause(after: .pause, phase: .listening))
    }

    private let loading = [
        PipelinePhase.requestingMicrophonePermission,
        .preparingEngine(EnginePreparationProgress(stage: .loadingModel)),
        .startingAudio,
    ]

    @Test("a start still loading is stopped and started again, with sounds only when alerts are on")
    func loadingIsStoppedAndStartedAgain() {
        for phase in loading {
            var off = CaptionsHandover()
            let stopStep = off.begin(phase: phase, soundAlertsOn: false)
            #expect(stopStep == .stop, "\(phase)")
            #expect(off.pausedHere)
            let back = off.comeback(phase: .idle)
            #expect(back == .start, "\(phase)")
            #expect(!off.pausedHere)
            var on = CaptionsHandover()
            let soundsStep = on.begin(phase: phase, soundAlertsOn: true)
            #expect(soundsStep == .stopKeepSounds, "\(phase)")
            let soundsBack = on.comeback(phase: .idle)
            #expect(soundsBack == .start)
        }
    }

    @Test("a failure with a retry lined up is stopped and started again")
    func retryWaitingIsStoppedAndStartedAgain() {
        var handover = CaptionsHandover()
        let step = handover.begin(phase: failed, soundAlertsOn: true, waitingForRetry: true)
        #expect(step == .stopKeepSounds)
        let back = handover.comeback(phase: .idle)
        #expect(back == .start)
        var plain = CaptionsHandover()
        let plainStep = plain.begin(phase: failed, soundAlertsOn: false, waitingForRetry: true)
        #expect(plainStep == .stop)
    }

    @Test("a failure nothing retries, idle and paused captions are left alone")
    func failureWithoutRetryIsLeftAlone() {
        for phase in [failed, .idle, .paused] {
            var handover = CaptionsHandover()
            let step = handover.begin(phase: phase, soundAlertsOn: true, waitingForRetry: false)
            #expect(step == .leaveAlone, "\(phase)")
            #expect(!handover.pausedHere)
            let back = handover.comeback(phase: .idle)
            #expect(back == .none)
        }
    }

    @Test("captions the person started meanwhile are not started twice, and stopped ones are not brought back")
    func changedMeanwhile() {
        var started = CaptionsHandover()
        _ = started.begin(phase: .startingAudio, soundAlertsOn: false)
        let whileRunning = started.comeback(phase: .listening)
        let again = started.comeback(phase: .idle)
        #expect(whileRunning == .none)
        #expect(again == .none)
        var stopped = CaptionsHandover()
        _ = stopped.begin(phase: failed, soundAlertsOn: false, waitingForRetry: true)
        let cancelled = stopped.cancel()
        let back = stopped.comeback(phase: .idle)
        let cancelledAgain = stopped.cancel()
        #expect(cancelled)
        #expect(back == .none)
        #expect(!cancelledAgain)
    }

    @Test("running captions come back by resuming, a stopped start only by starting")
    func comebackKinds() {
        var running = CaptionsHandover()
        _ = running.begin(phase: .listening, soundAlertsOn: true)
        let stopped = running.comeback(phase: .idle)
        #expect(stopped == .none)
        var paused = CaptionsHandover()
        _ = paused.begin(phase: .listening, soundAlertsOn: true)
        let resumed = paused.comeback(phase: .paused)
        #expect(resumed == .resume)
        var loadingStart = CaptionsHandover()
        _ = loadingStart.begin(phase: .startingAudio, soundAlertsOn: false)
        let notResumed = loadingStart.comeback(phase: .paused)
        #expect(notResumed == .none)
    }
}
