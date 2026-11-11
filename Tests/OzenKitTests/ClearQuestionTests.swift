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

    @Test("all three of its answers come in every interface language")
    func translated() {
        for (saving, failing) in [(true, false), (true, true), (false, false)] {
            let english = ClearQuestion.asked(saving: saving, failing: failing, in: .english).text
            for language in UILanguage.allCases where language != .english {
                let text = HebrewText.removingDirectionMarks(ClearQuestion.asked(saving: saving, failing: failing, in: language).text)
                #expect(text != english, "\(language), saving \(saving), failing \(failing)")
            }
        }
    }
}
