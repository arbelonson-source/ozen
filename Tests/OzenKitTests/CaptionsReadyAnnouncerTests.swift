import Testing
@testable import OzenKit

@Suite("Telling VoiceOver users that captions are ready after a long wait")
struct CaptionsReadyAnnouncerTests {
    private let stalled = PipelinePhase.failed(PipelineFailure(kind: .audioSessionFailed, detail: "stalled"))
    private let preparing = PipelinePhase.preparingEngine(EnginePreparationProgress(stage: .loadingModel))

    @Test("listening after a preparation of 12 seconds is announced as ready")
    func longPreparation() {
        var announcer = CaptionsStopAnnouncer()
        #expect(announcer.phaseChanged(to: preparing, at: 0) == nil)
        #expect(announcer.phaseChanged(to: .startingAudio, at: 11) == nil)
        #expect(announcer.phaseChanged(to: .listening, at: 12) == .ready)
        #expect(announcer.phaseChanged(to: .listening, at: 13) == nil)
    }

    @Test("a preparation of 3 seconds says nothing")
    func shortPreparation() {
        var announcer = CaptionsStopAnnouncer()
        #expect(announcer.phaseChanged(to: preparing, at: 0) == nil)
        #expect(announcer.phaseChanged(to: .listening, at: 3) == nil)
    }

    @Test("listening after a failure is back, never ready")
    func afterFailure() {
        var announcer = CaptionsStopAnnouncer()
        #expect(announcer.phaseChanged(to: preparing, at: 0) == nil)
        #expect(announcer.phaseChanged(to: stalled, at: 20) == .stopped)
        #expect(announcer.phaseChanged(to: preparing, at: 21) == nil)
        #expect(announcer.phaseChanged(to: .listening, at: 60) == .back)
    }

    @Test("pausing and resuming quickly says nothing")
    func resumeQuickly() {
        var announcer = CaptionsStopAnnouncer()
        #expect(announcer.phaseChanged(to: .listening, at: 0) == nil)
        #expect(announcer.phaseChanged(to: .paused, at: 100) == nil)
        #expect(announcer.phaseChanged(to: .startingAudio, at: 200) == nil)
        #expect(announcer.phaseChanged(to: .listening, at: 201) == nil)
    }

    @Test("a second long preparation in a later start is announced again")
    func secondStart() {
        var announcer = CaptionsStopAnnouncer()
        #expect(announcer.phaseChanged(to: preparing, at: 0) == nil)
        #expect(announcer.phaseChanged(to: .listening, at: 15) == .ready)
        #expect(announcer.phaseChanged(to: .idle, at: 100) == nil)
        #expect(announcer.phaseChanged(to: preparing, at: 200) == nil)
        #expect(announcer.phaseChanged(to: .listening, at: 230) == .ready)
    }

    @Test("stopping in the middle of a preparation resets the wait")
    func idleResets() {
        var announcer = CaptionsStopAnnouncer()
        #expect(announcer.phaseChanged(to: preparing, at: 0) == nil)
        #expect(announcer.phaseChanged(to: .idle, at: 5) == nil)
        #expect(announcer.phaseChanged(to: .startingAudio, at: 50) == nil)
        #expect(announcer.phaseChanged(to: .listening, at: 52) == nil)
    }

    @Test("ready reached while away is told once on return, and not after a pause there or when said in front")
    func readyWhileAway() {
        var away = CaptionsStopAnnouncer()
        _ = away.phaseChanged(to: preparing, active: false, at: 0)
        _ = away.phaseChanged(to: .listening, active: false, at: 60)
        #expect(away.comingBack(to: .listening) == .ready)
        #expect(away.comingBack(to: .listening) == nil)

        var paused = CaptionsStopAnnouncer()
        _ = paused.phaseChanged(to: preparing, active: false, at: 0)
        _ = paused.phaseChanged(to: .listening, active: false, at: 60)
        _ = paused.phaseChanged(to: .paused, active: false, at: 70)
        _ = paused.phaseChanged(to: .startingAudio, active: false, at: 80)
        _ = paused.phaseChanged(to: .listening, active: false, at: 81)
        #expect(paused.comingBack(to: .listening) == nil)

        var front = CaptionsStopAnnouncer()
        _ = front.phaseChanged(to: preparing, active: true, at: 0)
        #expect(front.phaseChanged(to: .listening, active: true, at: 12) == .ready)
        #expect(front.comingBack(to: .listening) == nil)
    }

    @Test("a ready owed through a failure that healed away is told on return, but not once captions were heard coming back")
    func readyOwedThroughFailure() {
        var healedAway = CaptionsStopAnnouncer()
        _ = healedAway.phaseChanged(to: preparing, active: false, at: 0)
        _ = healedAway.phaseChanged(to: .listening, active: false, at: 60)
        _ = healedAway.phaseChanged(to: stalled, active: false, at: 70)
        _ = healedAway.phaseChanged(to: preparing, active: false, at: 71)
        #expect(healedAway.phaseChanged(to: .listening, active: false, at: 72) == .back)
        #expect(healedAway.comingBack(to: .listening) == .ready)

        var backInFront = CaptionsStopAnnouncer()
        _ = backInFront.phaseChanged(to: preparing, active: false, at: 0)
        _ = backInFront.phaseChanged(to: .listening, active: false, at: 60)
        _ = backInFront.phaseChanged(to: stalled, active: false, at: 70)
        _ = backInFront.phaseChanged(to: preparing, active: false, at: 71)
        #expect(backInFront.comingBack(to: preparing) == nil)
        #expect(backInFront.phaseChanged(to: .listening, active: true, at: 72) == .back)
        #expect(backInFront.comingBack(to: .listening) == nil)
    }
}
