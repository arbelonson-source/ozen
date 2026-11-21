import Testing
@testable import OzenKit

@Suite("Fix a word form")
struct FixWordFormTests {
    let line = "I spoke with Shera yesterday"

    @Test("a typed word that is not in the line cannot be saved and the sheet says so, while one from the line can")
    func notInLine() {
        #expect(FixWordForm.changes(line: line, heard: "shera", written: "Shira"))
        #expect(FixWordForm.changes(line: line, heard: "spoke with", written: "spoke to"))
        #expect(FixWordForm.note(line: line, heard: "shera", written: "Shira") == nil)
        #expect(!FixWordForm.changes(line: line, heard: "zzzz", written: "Avi"))
        #expect(FixWordForm.note(line: line, heard: "zzzz", written: "Avi") == .notInLine)
        #expect(FixWordForm.note(line: line, heard: "Shira", written: "") == .notInLine)
    }

    @Test("tapped words build a run of up to four neighbours, as on Android")
    func tappingBuildsARun() {
        let words = ["one", "two", "three", "four", "five", "six"]
        var at: ClosedRange<Int>? = nil
        at = FixWordForm.tapped(at, index: 2, words: words)
        #expect(at == 2...2)
        at = FixWordForm.tapped(at, index: 3, words: words)
        #expect(at == 2...3)
        at = FixWordForm.tapped(at, index: 1, words: words)
        #expect(at == 1...3)
        at = FixWordForm.tapped(at, index: 0, words: words)
        #expect(at == 0...3)
        #expect(FixWordForm.tapped(at, index: 4, words: words) == 4...4)
        #expect(FixWordForm.tapped(2...2, index: 5, words: words) == 5...5)
    }

    @Test("a picked word tapped again is let go: the only one, or the end of a run joined by a near-miss")
    func tappingAgainLetsGo() {
        let words = ["one", "two", "three", "four"]
        #expect(FixWordForm.tapped(2...2, index: 2, words: words) == nil)
        #expect(FixWordForm.tapped(1...3, index: 1, words: words) == 2...3)
        #expect(FixWordForm.tapped(1...3, index: 3, words: words) == 1...2)
        #expect(FixWordForm.tapped(0...3, index: 2, words: words) == 0...1)
    }

    @Test("a run never reaches across punctuation")
    func notAcrossPunctuation() {
        let words = ["bat,", "sheva", "ok"]
        #expect(FixWordForm.tapped(0...0, index: 1, words: words) == 1...1)
        #expect(FixWordForm.tapped(1...1, index: 2, words: words) == 1...2)
    }

    @Test("a run reaches past a name's ending geresh, and the picked phrase can be saved")
    func runAfterAnEndingGeresh() {
        for geresh in ["\u{05F3}", "'", "\u{2019}"] {
            let shown = "ראיתי את ג\(geresh)ורג\(geresh) בוש אתמול"
            let words = shown.split(separator: " ").map(String.init)
            #expect(FixWordForm.tapped(2...2, index: 3, words: words) == 2...3, "\(shown)")
            #expect(FixWordForm.tapped(3...3, index: 2, words: words) == 2...3, "\(shown)")
            #expect(FixWordForm.changes(line: shown, heard: "ג\(geresh)ורג\(geresh) בוש", written: "George Bush"), "\(shown)")
        }
        #expect(FixWordForm.tapped(2...2, index: 3, words: ["ראיתי", "את", "ג׳ורג׳,", "בוש"]) == 3...3)
    }

    @Test("a run stops at the closing quote of a quoted Hebrew word, and a quoted name keeps its closing quote when fixed")
    func runAndQuotes() {
        let quoted = "אמרתי ‘שלום’ לכולם"
        let words = quoted.split(separator: " ").map(String.init)
        #expect(FixWordForm.tapped(1...1, index: 2, words: words) == 2...2)
        #expect(FixWordForm.tapped(2...2, index: 1, words: words) == 1...1)
        #expect(WordFixes.apply([WordFix(heard: "שלום", written: "Shalom")], to: quoted) == "אמרתי ‘Shalom’ לכולם")
        let name = "ראיתי את ‘ג׳ורג׳ בוש’ אתמול"
        let tiles = name.split(separator: " ").map(String.init)
        #expect(FixWordForm.tapped(2...2, index: 3, words: tiles) == 2...3)
        #expect(FixWordForm.tapped(3...3, index: 4, words: tiles) == 4...4)
        #expect(FixWordForm.tapped(4...4, index: 3, words: tiles) == 3...3)
        #expect(FixWordForm.changes(line: name, heard: "ג׳ורג׳ בוש", written: "George Bush"))
        #expect(WordFixes.apply([WordFix(heard: "ג׳ורג׳ בוש", written: "George Bush")], to: name) == "ראיתי את ‘George Bush’ אתמול")
    }

    @Test("a word picked or typed with nothing written yet asks for how it should be written")
    func nowWrite() {
        #expect(FixWordForm.note(line: line, heard: "Shera", written: "") == .nowWrite)
        #expect(FixWordForm.note(line: line, heard: "Shera", written: "  ") == .nowWrite)
        #expect(FixWordForm.note(line: line, heard: "", written: "") == nil)
        #expect(FixWordForm.note(line: line, heard: "Shera", written: "Shira") == nil)
    }

    @Test("a number picked from the screen, drawn with no-break spaces and hyphens, still counts as in the line")
    func pickedNumber() {
        let call = "Call 050-123 4567 tomorrow"
        let drawn = CaptionLayout.displayText(call, languageCode: "en")
        let picked = drawn.split(whereSeparator: \.isWhitespace).dropFirst().first.map(String.init) ?? ""
        let heard = FixWordForm.cleaned(picked)
        #expect(!heard.contains("\u{00A0}") && !heard.contains("\u{2011}"))
        #expect(FixWordForm.changes(line: call, heard: heard, written: "050-123 4568"))
        #expect(FixWordForm.note(line: call, heard: heard, written: "") == .nowWrite)
    }

    @Test("a heard side with no letter or digit is refused as on Android, since it can never match a word")
    func heardWithNoLetterIsRefused() {
        #expect(FixWordForm.refusal(WordFix(heard: "...", written: "Avi"), to: []) == .heardWords)
        #expect(!FixWordForm.hasHeardWord("... !"))
        #expect(FixWordForm.hasHeardWord("aval..."))
        #expect(FixWordForm.refusal(WordFix(heard: "aval...", written: "Avi"), to: []) == nil)
        #expect(FixWordForm.refusal(WordFix(heard: "...", written: "..."), to: []) == .sameWord)
        #expect(FixWordForm.refusal(WordFix(heard: "", written: "Avi"), to: []) == .missing)
        let full = (0..<WordFixes.maximumFixes).map { WordFix(heard: "word\($0)", written: "Word \($0)") }
        #expect(FixWordForm.refusal(WordFix(heard: "...", written: "Avi"), to: full) == .heardWords)
        #expect(FixWordForm.refusal(WordFix(heard: "aval", written: "Avi"), to: full) == .full)
        #expect(WordFixes.apply([WordFix(heard: "...", written: "Avi")], to: "Wait ... ok") == "Wait ... ok")
    }

    @Test("Fix it every time with the list full says so, as the Words screen does, unless the fix replaces one already there")
    func fullListIsSaidUnderFixItEveryTime() {
        let full = (0..<WordFixes.maximumFixes).map { WordFix(heard: "word\($0)", written: "Word \($0)") }
        let shown = "I saw shera and word7 today"
        #expect(FixWordForm.keepRefusal(line: shown, heard: "shera", written: "Shira", to: full) == .full)
        #expect(FixWordForm.keepRefusal(line: shown, heard: "word7", written: "Word Seven", to: full) == nil)
        #expect(FixWordForm.keepRefusal(line: shown, heard: "shera", written: "Shira", to: Array(full.dropLast())) == nil)
        #expect(FixWordForm.keepRefusal(line: shown, heard: "shera", written: "", to: full) == nil)
        #expect(FixWordForm.keepRefusal(line: shown, heard: "zzzz", written: "Avi", to: full) == nil)
    }
}
