import Testing
@testable import OzenKit

@Suite("The question before clearing the captions")
struct ClearQuestionTests {
    @Test("it promises History only while saving is on and working, and says so")
    func promise() {
        let kept = ClearQuestion.asked(saving: true, failing: false, in: .english)
        #expect(kept.keepsHistory)
        #expect(kept.text == "They stay saved in Settings, under “Previous conversations”.")
        let failing = ClearQuestion.asked(saving: true, failing: true, in: .english)
        #expect(!failing.keepsHistory)
        #expect(failing.text == "Saving to History isn’t working right now, so they won’t be saved.")
        let off = ClearQuestion.asked(saving: false, failing: false, in: .english)
        #expect(!off.keepsHistory)
        #expect(off.text == "History is off, so they won’t be saved.")
        #expect(!ClearQuestion.asked(saving: false, failing: true, in: .english).keepsHistory)
    }

    @Test("its words come in every interface language")
    func translated() {
        for language in UILanguage.allCases where language != .english {
            let text = ClearQuestion.asked(saving: true, failing: false, in: language).text
            #expect(text != "They stay saved in Settings, under “Previous conversations”.", "\(language)")
        }
    }
}
