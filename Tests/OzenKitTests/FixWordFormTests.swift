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
}
