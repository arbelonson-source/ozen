import Testing
@testable import OzenKit

@Suite("Renaming a saved person onto another saved name is a merge")
struct SavedPersonMergeTests {
    @Test("a name another saved person has is a merge into that person")
    func sameName() {
        #expect(SavedPersonMerge.target(renaming: "Dana", to: "Yossi", savedNames: ["Dana", "Yossi"]) == "Yossi")
    }

    @Test("case and spaces around the name do not hide it, and the saved spelling is the one to keep")
    func caseAndSpaces() {
        #expect(SavedPersonMerge.target(renaming: "Dana", to: "  yossi ", savedNames: ["Dana", "Yossi"]) == "Yossi")
    }

    @Test("an unused name, the person's own name, or a case-only change of it is no merge")
    func noMerge() {
        #expect(SavedPersonMerge.target(renaming: "Dana", to: "Dani", savedNames: ["Dana", "Yossi"]) == nil)
        #expect(SavedPersonMerge.target(renaming: "Dana", to: "Dana", savedNames: ["Dana", "Yossi"]) == nil)
        #expect(SavedPersonMerge.target(renaming: "Dana", to: "dana", savedNames: ["Dana", "Yossi"]) == nil)
        #expect(SavedPersonMerge.target(renaming: "Dana", to: "   ", savedNames: ["Dana", "Yossi"]) == nil)
    }

    @Test("a person saved with several voice prints is no other person")
    func severalPrints() {
        #expect(SavedPersonMerge.target(renaming: "Dana", to: "Daniela", savedNames: ["Dana", "Dana", "Yossi"]) == nil)
    }

    @Test("a merge lands on the saved name exactly as stored, even with spaces around it, so no person is left apart")
    func paddedSavedName() {
        let saved = ["Dana", "Yossi "]
        #expect(SavedPersonMerge.target(renaming: "Dana", to: "yossi", savedNames: saved) == "Yossi ")
        #expect(SavedPersonMerge.destination(renaming: "Dana", to: "yossi", savedNames: saved) == "Yossi ")
        #expect(SavedPersonMerge.destination(renaming: "Dana", to: "  Dani ", savedNames: saved) == "Dani")
    }
}
