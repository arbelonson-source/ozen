import Testing
@testable import OzenKit

@Suite("Problem report line")
struct ProblemReportLineTests {
    @Test("the report's first line says when the problem was marked, in the app's language")
    func line() {
        Localization.$override.withValue(.english) {
            #expect(ProblemReportLine.text(markedAt: "14:02") == "Ozen on this phone: a captions problem was marked at 14:02.")
        }
        Localization.$override.withValue(.hebrew) {
            #expect(ProblemReportLine.text(markedAt: "14:02") == "אוזן בטלפון הזה: סומנה בעיה בכתוביות בשעה 14:02.")
        }
    }

    @Test("the report sent from the caption screen is that line on top of the diagnostics report, which stays as it was")
    func onTop() {
        Localization.$override.withValue(.english) {
            let diagnostics = "Ozen diagnostics\nphase: listening\njournal, previous runs included (oldest first):\n-"
            #expect(ProblemReportLine.onTop(of: diagnostics, markedAt: "14:02")
                == "Ozen on this phone: a captions problem was marked at 14:02.\nOzen diagnostics\nphase: listening\njournal, previous runs included (oldest first):\n-")
        }
    }
}
