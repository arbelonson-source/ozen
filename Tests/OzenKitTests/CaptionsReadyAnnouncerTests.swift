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
}
