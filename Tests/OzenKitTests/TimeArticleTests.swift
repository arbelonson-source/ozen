import Foundation
import Testing
@testable import OzenKit

@Suite("One o'clock takes the singular article in Spanish and Portuguese")
struct TimeArticleTests {
    @Test("Spanish: a la 1:05, but a las 13:05, 10:00 and 11:30")
    func spanish() {
        #expect(TimeArticle.agreeing("hoy a las 1:05", time: "1:05", in: .spanish) == "hoy a la 1:05")
        #expect(TimeArticle.agreeing("hacia las 1:05 p. m. y", time: "1:05 p. m.", in: .spanish) == "hacia la 1:05 p. m. y")
        #expect(TimeArticle.agreeing("hoy a las 13:05", time: "13:05", in: .spanish) == "hoy a las 13:05")
        #expect(TimeArticle.agreeing("hoy a las 10:00", time: "10:00", in: .spanish) == "hoy a las 10:00")
        #expect(TimeArticle.agreeing("hoy a las 11:30", time: "11:30", in: .spanish) == "hoy a las 11:30")
    }

    @Test("Portuguese: à 01:05 and por volta da 1:05, but às 21:05")
    func portuguese() {
        #expect(TimeArticle.agreeing("hoje às 01:05", time: "01:05", in: .portuguese) == "hoje à 01:05")
        #expect(TimeArticle.agreeing("por volta das 1:05 e", time: "1:05", in: .portuguese) == "por volta da 1:05 e")
        #expect(TimeArticle.agreeing("hoje às 21:05", time: "21:05", in: .portuguese) == "hoje às 21:05")
    }

    @Test("the phone's own digits count too: a phone set to Arabic-Indic or Devanagari numbers still gets la ١:٠٥")
    func otherDigits() {
        #expect(TimeArticle.agreeing("hoy a las ١:٠٥", time: "١:٠٥", in: .spanish) == "hoy a la ١:٠٥")
        #expect(TimeArticle.agreeing("hoje às १:०५", time: "१:०५", in: .portuguese) == "hoje à १:०५")
        #expect(TimeArticle.agreeing("hoy a las ١٣:٠٥", time: "١٣:٠٥", in: .spanish) == "hoy a las ١٣:٠٥")
        #expect(TimeArticle.hour(of: "٠١:٠٥") == 1)
        #expect(TimeArticle.hour(of: "p. m.") == nil)
    }

    @Test("other languages are left as they are")
    func others() {
        for language in UILanguage.allCases where language != .spanish && language != .portuguese {
            #expect(TimeArticle.agreeing("las 1:05 às 1:05 das 1:05", time: "1:05", in: language) == "las 1:05 às 1:05 das 1:05", "\(language)")
        }
    }

    @Test("the problem report's first line and the listening-was-off note say it too")
    func realSentences() throws {
        Localization.$override.withValue(.spanish) {
            #expect(ProblemReportLine.text(markedAt: "1:05").hasSuffix(" a la 1:05."))
            #expect(ProblemReportLine.text(markedAt: "14:02").hasSuffix(" a las 14:02."))
        }
        Localization.$override.withValue(.portuguese) {
            #expect(ProblemReportLine.text(markedAt: "01:05").hasSuffix(" à 01:05."))
        }
        let utc = try #require(TimeZone(identifier: "UTC"))
        let atTwentyPastOne = ListeningCut.Notice(stoppedAt: 80 * 60, offSeconds: 600)
        let atTwentyPastTen = ListeningCut.Notice(stoppedAt: 10 * 3_600 + 80 * 60 - 3_600, offSeconds: 600)
        Localization.$override.withValue(.portuguese) {
            #expect(atTwentyPastOne.text(timeZone: utc).contains("por volta da "))
            #expect(atTwentyPastTen.text(timeZone: utc).contains("por volta das "))
        }
        Localization.$override.withValue(.spanish) {
            #expect(atTwentyPastOne.text(timeZone: utc).contains("hacia la "))
            #expect(atTwentyPastTen.text(timeZone: utc).contains("hacia las "))
        }
    }
}
