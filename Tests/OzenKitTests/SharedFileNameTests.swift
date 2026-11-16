import Testing
@testable import OzenKit

@Suite("Names for shared files")
struct SharedFileNameTests {
    private func hasNoSeparatorOrControl(_ name: String) -> Bool {
        !name.unicodeScalars.contains { "/\\:*?\"<>|".unicodeScalars.contains($0) || $0.properties.generalCategory == .control }
    }

    @Test("a name that climbs out of its folder cannot")
    func climbing() {
        let name = SharedFileName.make(from: "../x")
        #expect(name == "_x")
        #expect(hasNoSeparatorOrControl(name))
    }

    @Test("separators and the characters other systems refuse become underscores")
    func separators() {
        #expect(SharedFileName.make(from: "a/b:c") == "a_b_c")
        #expect(SharedFileName.make(from: "who? \"me\" <you>|*") == "who_ _me_ _you___")
        let broken = SharedFileName.make(from: "line\nbreak\u{0007}")
        #expect(broken.unicodeScalars.map(\.value) == Array("line_break_".unicodeScalars.map(\.value)))
    }

    @Test("nothing usable falls back to a plain word")
    func empty() {
        for name in ["", "   ", ".", "..", "...", " . . "] {
            #expect(SharedFileName.make(from: name) == "recording", "for \"\(name)\"")
        }
    }

    @Test("a long name is cut to eighty characters")
    func long() {
        #expect(SharedFileName.make(from: String(repeating: "a", count: 300)) == String(repeating: "a", count: 80))
    }

    @Test("an emoji is never cut in half, and the name stays short in bytes too")
    func emoji() {
        let microphone = SharedFileName.make(from: "🎤" + String(repeating: "x", count: 100))
        #expect(microphone.count == 80)
        #expect(microphone.hasPrefix("🎤x"))
        let family = SharedFileName.make(from: String(repeating: "👨‍👩‍👧", count: 100))
        #expect(family.utf8.count <= 200)
        #expect(!family.isEmpty)
        #expect(family.allSatisfy { $0 == "👨‍👩‍👧" })
    }

    @Test("direction marks that could disguise the end of a name are left out")
    func directionMarks() {
        func scalars(_ text: String) -> [UInt32] { text.unicodeScalars.map(\.value) }
        #expect(scalars(SharedFileName.make(from: "note\u{202E}tvs")) == scalars("notetvs"))
        #expect(scalars(SharedFileName.make(from: "\u{200F}שיחה\u{200E}")) == scalars("שיחה"))
    }

    @Test("a zero-width space is left out like the other invisible marks")
    func zeroWidthSpace() {
        func scalars(_ text: String) -> [UInt32] { text.unicodeScalars.map(\.value) }
        #expect(scalars(SharedFileName.make(from: "a\u{200B}b")) == scalars("ab"))
        #expect(SharedFileName.make(from: "\u{200B}\u{200B}") == "recording")
    }

    @Test("an ordinary name, Hebrew or not, stays as it is")
    func ordinary() {
        #expect(SharedFileName.make(from: "Lecture 4") == "Lecture 4")
        #expect(SharedFileName.make(from: "הרצאה 4") == "הרצאה 4")
        #expect(SharedFileName.make(from: "PTT-2026.10.16-WA0003") == "PTT-2026.10.16-WA0003")
    }

    @Test("emoji tag characters are dropped except inside a flag")
    func tagCharacters() {
        let tags = "\u{E0067}\u{E0062}\u{E0065}\u{E006E}\u{E0067}\u{E007F}"
        let flag = "\u{1F3F4}" + tags
        #expect(SharedFileName.make(from: "a" + flag) == "a" + flag)
        #expect(SharedFileName.make(from: "a\u{E0041}\u{E007F}b") == "ab")
        #expect(SharedFileName.make(from: "\u{1F3F4}\u{E0067}") == "\u{1F3F4}")
    }

    @Test("a joiner stays between letters, marks or pictures, and goes elsewhere")
    func joiners() {
        #expect(SharedFileName.make(from: "a\u{200D}b") == "a\u{200D}b")
        #expect(SharedFileName.make(from: "a \u{200D} b") == "a  b")
        #expect(SharedFileName.make(from: "\u{200D}ab\u{200D}") == "ab")
        #expect(SharedFileName.make(from: "a\u{200D}\u{F0000}") == "a\u{F0000}")
        #expect(SharedFileName.make(from: "\u{F0000}\u{200D}a") == "\u{F0000}a")
        #expect(SharedFileName.make(from: "a\u{20DD}\u{200D}b") == "a\u{20DD}\u{200D}b")
        #expect(SharedFileName.make(from: "\u{1F468}\u{200D}\u{1F469}") == "\u{1F468}\u{200D}\u{1F469}")
    }

    @Test("a non-joiner at the ends is trimmed with the dots")
    func edgeNonJoiner() {
        #expect(SharedFileName.make(from: "\u{200C}.ab.\u{200C}") == "ab")
        #expect(SharedFileName.make(from: "a\u{200C}b") == "a\u{200C}b")
    }
}
