import Foundation
import Testing
@testable import Ozen
@testable import OzenKit

@Suite("Status line while captions are let go for a recording")
@MainActor
struct RecordingPausePresentationTests {
    private func shown(_ phase: PipelinePhase, soundsOnly: Bool = false, alertsPaused: Bool = false, call: Bool = false, held: Bool = true) -> PhasePresentation {
        PhasePresentation(phase: phase, engine: nil, interruptedBySystem: call, soundsOnly: soundsOnly, pausedForRecording: held, soundAlertsPaused: alertsPaused)
    }

    @Test("sounds only for a recording says captions are paused and come back by themselves")
    func soundsOnly() {
        let status = shown(.paused, soundsOnly: true)
        #expect(status.title == "הכתוביות מושהות בזמן שהקלטה הופכת לטקסט")
        #expect(status.detail == "הן יחזרו לבד כשהיא תסתיים")
        #expect(status.action == .none)
        #expect(shown(.idle, soundsOnly: true).action == .none)
        #expect(status.detailFitsInStatus)
    }

    @Test("sound alerts that are not listening are named too")
    func alertsPaused() {
        let status = shown(.paused, alertsPaused: true)
        #expect(status.title == "הכתוביות והתראות הצליל מושהות בזמן שהקלטה הופכת לטקסט")
    }

    @Test("a start stopped for a recording says captions are paused and come back, not that they are off")
    func stoppedForRecording() {
        let status = shown(.idle)
        #expect(status.title == "הכתוביות מושהות בזמן שהקלטה הופכת לטקסט")
        #expect(status.action == .none)
        #expect(shown(.idle, alertsPaused: true).title == "הכתוביות והתראות הצליל מושהות בזמן שהקלטה הופכת לטקסט")
    }

    @Test("while captions are held for a recording the tile offers no tap, and the usual one is back once the hold ends")
    func noTapWhileHeld() {
        #expect(shown(.paused).action == .none)
        #expect(shown(.idle).action == .none)
        #expect(shown(.paused, held: false).action == .resume)
        #expect(shown(.idle, held: false).action == .start)
    }

    @Test("captions the person stopped, or a call, are not described as paused for a recording")
    func otherwiseUnchanged() {
        #expect(shown(.idle, held: false).title == "הכתוביות כבויות")
        #expect(shown(.paused, call: true).title != shown(.paused).title)
        #expect(shown(.listening).title == "מאזינה")
        #expect(shown(.paused, held: false).title == "מושהה")
    }
}
