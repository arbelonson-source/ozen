import Testing
@testable import OzenKit

@Suite("Putting back a quick phrase that was deleted")
struct QuickPhraseUndoTests {
    private let list = ["one", "two", "three", "four"]

    @Test("a deleted phrase goes back in the place it had")
    func backInPlace() {
        let removed = QuickPhraseUndo.removed(at: [1], from: list)
        #expect(removed == [RemovedQuickPhrase(phrase: "two", index: 1)])
        let without = ["one", "three", "four"]
        #expect(QuickPhraseUndo.restoring(removed, into: without) == list)
    }

    @Test("several deleted phrases go back in their places")
    func severalBack() {
        let removed = QuickPhraseUndo.removed(at: [0, 2], from: list)
        #expect(QuickPhraseUndo.restoring(removed, into: ["two", "four"]) == list)
    }

    @Test("the last phrase goes back at the end, and a list that shrank still takes it")
    func endAndShortList() {
        let removed = QuickPhraseUndo.removed(at: [3], from: list)
        #expect(QuickPhraseUndo.restoring(removed, into: ["one", "two", "three"]) == list)
        #expect(QuickPhraseUndo.restoring(removed, into: ["one"]) == ["one", "four"])
    }

    @Test("a phrase that is already back is not added twice")
    func notTwice() {
        let removed = QuickPhraseUndo.removed(at: [1], from: list)
        #expect(QuickPhraseUndo.restoring(removed, into: list) == list)
    }

    @Test("places that are not in the list delete nothing and remember nothing")
    func outOfRange() {
        #expect(QuickPhraseUndo.removed(at: [9], from: list).isEmpty)
    }
}
