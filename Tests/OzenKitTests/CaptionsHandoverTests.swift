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
}
