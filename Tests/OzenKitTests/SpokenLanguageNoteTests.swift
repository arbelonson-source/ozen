import Testing
@testable import OzenKit

@Suite("What the app says about a spoken language its speech models barely know")
struct SpokenLanguageNoteTests {
    @Test("Amharic on the phone's model or the home computer says captions are often wrong")
    func amharicWarns() {
        Localization.$override.withValue(.english) {
            let note = "Captions in Amharic are often wrong for now. Check anything important with the person speaking."
            #expect(SpokenLanguageNote.text(for: .amharic, engine: .whisperKit) == note)
            #expect(SpokenLanguageNote.text(for: .amharic, engine: .homeServer) == note)
            #expect(SpokenLanguageNote.text(for: .amharic, engine: .appleSpeech) == note)
        }
    }

    @Test("a cloud service, or any other language, adds nothing")
    func othersSayNothing() {
        #expect(SpokenLanguageNote.text(for: .amharic, engine: .cloud) == nil)
        for language in UILanguage.allCases where language != .amharic {
            for engine in TranscriptionEngineKind.allCases {
                #expect(SpokenLanguageNote.text(for: language, engine: engine) == nil)
            }
        }
    }
}
