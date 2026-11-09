import Testing
@testable import OzenKit

@Suite("Telling VoiceOver users that captions stopped or came back")
struct CaptionsStopAnnouncerTests {
    private let stalled = PipelinePhase.failed(PipelineFailure(kind: .audioSessionFailed, detail: "stalled"))
    private let preparing = PipelinePhase.preparingEngine(EnginePreparationProgress(stage: .loadingModel))

    @Test("a failure is announced once through its retries, and the return to listening after it")
    func failureAndRecovery() {
        var announcer = CaptionsStopAnnouncer()
        let events = [
            PipelinePhase.startingAudio, .listening,
            stalled, preparing,
            .failed(PipelineFailure(kind: .noAudioInputs, detail: "")),
            .startingAudio, .listening, .listening,
        ].map { announcer.phaseChanged(to: $0) }
        #expect(events == [nil, nil, .stopped, nil, nil, nil, .back, nil])
    }

    @Test("starting, pausing and stopping on purpose announce nothing")
    func onPurpose() {
        var announcer = CaptionsStopAnnouncer()
        let events = [PipelinePhase.requestingMicrophonePermission, preparing, .startingAudio, .listening, .paused, .listening, .idle]
            .map { announcer.phaseChanged(to: $0) }
        #expect(events.allSatisfy { $0 == nil })
    }

    @Test("stopped by hand after a failure, starting again later isn't a recovery")
    func stoppedAfterFailure() {
        var announcer = CaptionsStopAnnouncer()
        #expect(announcer.phaseChanged(to: stalled) == .stopped)
        #expect(announcer.phaseChanged(to: .idle) == nil)
        #expect(announcer.phaseChanged(to: .listening) == nil)
        #expect(announcer.phaseChanged(to: stalled) == .stopped)
    }

    @Test("a stop while away is told once on return, and not when it healed or happened in front")
    func stopWhileAway() {
        var away = CaptionsStopAnnouncer()
        #expect(away.phaseChanged(to: stalled, active: false) == .stopped)
        #expect(away.comingBack(to: stalled) == .stopped)
        #expect(away.comingBack(to: stalled) == nil)

        var healed = CaptionsStopAnnouncer()
        _ = healed.phaseChanged(to: stalled, active: false)
        _ = healed.phaseChanged(to: .listening, active: false)
        #expect(healed.comingBack(to: .listening) == nil)

        var front = CaptionsStopAnnouncer()
        _ = front.phaseChanged(to: stalled, active: true)
        #expect(front.comingBack(to: stalled) == nil)
    }

    @Test("back in front while captions restart, a second failure there is still told")
    func failingAgainInFront() {
        var announcer = CaptionsStopAnnouncer()
        #expect(announcer.phaseChanged(to: stalled, active: false) == .stopped)
        #expect(announcer.phaseChanged(to: preparing, active: false) == nil)
        #expect(announcer.comingBack(to: preparing) == nil)
        #expect(announcer.phaseChanged(to: stalled, active: true) == .stopped)
        #expect(announcer.phaseChanged(to: stalled, active: true) == nil)
    }

    @Test("leaving again while captions restart and fail keeps the stop owed for the return")
    func failingAgainAway() {
        var announcer = CaptionsStopAnnouncer()
        _ = announcer.phaseChanged(to: stalled, active: false)
        _ = announcer.phaseChanged(to: preparing, active: false)
        #expect(announcer.phaseChanged(to: stalled, active: false) == nil)
        #expect(announcer.comingBack(to: stalled) == .stopped)
    }
}
