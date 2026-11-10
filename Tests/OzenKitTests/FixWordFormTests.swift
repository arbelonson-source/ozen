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
