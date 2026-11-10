import Testing
@testable import OzenKit

@Suite("What the app says about a spoken language its speech models barely know")
struct SpokenLanguageNoteTests {
    @Test("Amharic says captions are often wrong")
    func amharicWarns() {
        Localization.$override.withValue(.english) {
            #expect(SpokenLanguageNote.text(for: .amharic) == "Captions in Amharic are often wrong for now. Check anything important with the person speaking.")
        }
    }

    @Test("any other language adds nothing")
    func othersSayNothing() {
        for language in UILanguage.allCases where language != .amharic {
            #expect(SpokenLanguageNote.text(for: language) == nil)
        }
    }
}
