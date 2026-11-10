import Foundation
import Testing
@testable import Ozen
@testable import OzenKit

@Suite("Status line while only sounds are listened for")
@MainActor
struct SoundsOnlyPresentationTests {
    @Test("paused and idle say sounds only, and a tap brings captions back")
    func titleDetailAndTap() {
        let paused = PhasePresentation(phase: .paused, engine: nil, interruptedBySystem: false, soundsOnly: true)
        #expect(paused.title == "מאזינה לצלילים בלבד")
        #expect(paused.detail == "הקישו כדי להחזיר כתוביות")
        #expect(paused.action == .resume)
        let idle = PhasePresentation(phase: .idle, engine: nil, interruptedBySystem: false, soundsOnly: true)
        #expect(idle.title == paused.title)
        #expect(idle.action == .start)
        #expect(paused.detailFitsInStatus)
    }

    @Test("without sounds only the status is as before, and a call still says so")
    func otherwiseUnchanged() {
        #expect(PhasePresentation(phase: .paused, engine: nil, interruptedBySystem: false).title == "מושהה")
        #expect(PhasePresentation(phase: .idle, engine: nil, interruptedBySystem: false).title == "הכתוביות כבויות")
        #expect(PhasePresentation(phase: .paused, engine: nil, interruptedBySystem: true, soundsOnly: true).title != "מאזינה לצלילים בלבד")
    }
}
