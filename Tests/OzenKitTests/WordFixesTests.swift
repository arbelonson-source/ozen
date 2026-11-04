import Foundation
import Testing
@testable import OzenKit

@Suite("WordFixes")
struct WordFixesTests {
    private func fix(_ heard: String, _ written: String) -> WordFix { WordFix(heard: heard, written: written) }
    private let avi = [WordFix(heard: "aval", written: "Avi")]

    @Test("rule 1: whole words only, ignoring case")
    func wholeWords() {
        #expect(WordFixes.apply(avi, to: "I saw aval today") == "I saw Avi today")
        #expect(WordFixes.apply(avi, to: "Aval") == "Avi")
        #expect(WordFixes.apply(avi, to: "it is available") == "it is available")
    }

    @Test("rule 2: surrounding punctuation stays")
    func punctuation() {
        #expect(WordFixes.apply(avi, to: "aval, come") == "Avi, come")
        #expect(WordFixes.apply(avi, to: "(aval)") == "(Avi)")
        #expect(WordFixes.apply(avi, to: "aval?") == "Avi?")
    }

    @Test("rule 3: Hebrew prefix letters are kept")
    func hebrewPrefix() {
        let rotem = [fix("רותם", "רותי")]
        #expect(WordFixes.apply(rotem, to: "ורותם באה") == "ורותי באה")
        #expect(WordFixes.apply(rotem, to: "לרותם") == "לרותי")
        #expect(WordFixes.apply(rotem, to: "שלרותם") == "שלרותי")
        #expect(WordFixes.apply(rotem, to: "ורותמים") == "ורותמים")
        #expect(WordFixes.apply(rotem, to: "אלרותם") == "אלרותם")
        #expect(WordFixes.apply(rotem, to: "ושלהרותם") == "ושלהרותם")
        #expect(WordFixes.apply(rotem, to: "רותם") == "רותי")
    }

    @Test("rule 4: niqqud is ignored when matching, written is verbatim")
    func niqqud() {
        let rotem = [fix("רותם", "רוּתִי")]
        #expect(WordFixes.apply(rotem, to: "רוֹתֵם") == "רוּתִי")
        #expect(WordFixes.apply([fix("רוֹתֵם", "X")], to: "רותם") == "X")
        #expect(WordFixes.apply(rotem, to: "לְרוֹתֵם") == "לְרוּתִי")
    }

    @Test("rule 5: a phrase matches across single spaces")
    func phrases() {
        let bat = [fix("bat sheva", "Batsheva")]
        #expect(WordFixes.apply(bat, to: "I met Bat Sheva.") == "I met Batsheva.")
        #expect(WordFixes.apply(bat, to: "bat  sheva") == "bat  sheva")
        #expect(WordFixes.apply(bat, to: "bat, sheva") == "bat, sheva")
        #expect(WordFixes.apply([fix("a b c d", "Z")], to: "x a b c d y") == "x Z y")
    }

    @Test("rule 6: longer phrase wins and there is no chaining")
    func longerWins() {
        let list = [fix("bat", "B"), fix("bat sheva", "Batsheva")]
        #expect(WordFixes.apply(list, to: "bat sheva") == "Batsheva")
        #expect(WordFixes.apply(list, to: "bat") == "B")
        let chain = [fix("a", "b"), fix("b", "c")]
        #expect(WordFixes.apply(chain, to: "a") == "b")
        #expect(WordFixes.apply(chain, to: "a b") == "b c")
    }

    @Test("rule 7: normalized trims, drops, clips, dedupes and caps")
    func normalizing() {
        let result = WordFixes.normalized([
            fix("  aval ", " Avi "), fix("", "x"), fix("y", ""), fix("Same", "same"),
            fix("AVAL", "Other"), fix("רוֹתם", "ר"), fix("רותם", "ז"),
        ])
        #expect(result == [fix("aval", "Avi"), fix("רוֹתם", "ר")])
        let long = String(repeating: "a", count: 60)
        let clipped = WordFixes.normalized([fix(long, String(repeating: "b", count: 60))])
        #expect(clipped.first?.heard.count == 40)
        #expect(clipped.first?.written.count == 40)
        let many = (0..<150).map { fix("w\($0)", "v\($0)") }
        let capped = WordFixes.normalized(many)
        #expect(capped.count == 100)
        #expect(capped.first == fix("w0", "v0"))
    }

    @Test("rule 8: no fixes leaves the text unchanged")
    func empty() {
        #expect(WordFixes.apply([], to: "  hello,  world ") == "  hello,  world ")
        #expect(WordFixes.apply(avi, to: "") == "")
    }
}
