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

    @Test("captions paused by a phrase right as they came on are still said ready, from the steps reached in order")
    func readyBeforeTheHold() {
        var steps = CaptionStepsReached()
        steps.reached(preparing, at: 100)
        steps.reached(.startingAudio, at: 112)
        steps.reached(.listening, at: 112.5)
        steps.reached(.paused, at: 112.6)
        var announcer = CaptionsStopAnnouncer()
        #expect(announcer.stepsReached(steps.take()) == .ready)
        #expect(steps.take().isEmpty)

        var lastOnly = CaptionsStopAnnouncer()
        _ = lastOnly.phaseChanged(to: preparing, at: 100)
        #expect(lastOnly.phaseChanged(to: .paused, at: 112.6) == nil)
    }

    @Test("several steps at once say only the last news: back after a stop, stopped after a return")
    func lastNewsOfSeveral() {
        var announcer = CaptionsStopAnnouncer()
        let stopAndBack = [CaptionStepsReached.Step(stalled, at: 1), .init(.startingAudio, at: 2), .init(.listening, at: 3)]
        #expect(announcer.stepsReached(stopAndBack) == .back)
        #expect(announcer.stepsReached([.init(stalled, at: 4), .init(preparing, at: 5)]) == .stopped)
        #expect(announcer.stepsReached([]) == nil)
    }

    @Test("away, the steps are kept for the return, as a single step was")
    func awaySteps() {
        var announcer = CaptionsStopAnnouncer()
        _ = announcer.stepsReached([.init(preparing, at: 0), .init(.listening, at: 30), .init(.listening, at: 31)], active: false)
        #expect(announcer.comingBack(to: .listening) == .ready)
        #expect(announcer.comingBack(to: .listening) == nil)
    }

    @Test("the steps keep a preparation without its fraction, and only the newest few")
    func stepsKept() {
        var steps = CaptionStepsReached()
        steps.reached(.preparingEngine(EnginePreparationProgress(stage: .downloadingModel, fraction: 0.4)), at: 0)
        #expect(steps.take() == [.init(.preparingEngine(EnginePreparationProgress(stage: .downloadingModel)), at: 0)])
        for second in 0..<(CaptionStepsReached.kept + 5) { steps.reached(.listening, at: Double(second)) }
        let kept = steps.take()
        #expect(kept.count == CaptionStepsReached.kept)
        #expect(kept.first?.at == 5)
    }
}
