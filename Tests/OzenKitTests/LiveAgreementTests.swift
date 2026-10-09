import Foundation
import Testing
@testable import OzenKit

@Suite("LiveAgreement")
struct LiveAgreementTests {
    @Test("a line that only grows is shown exactly as the engine wrote it")
    func growing() {
        var agreement = LiveAgreement()
        #expect(agreement.settle("good morning") == "good morning")
        #expect(agreement.settle("good morning  how did") == "good morning  how did")
        #expect(agreement.settle("good morning how did you sleep") == "good morning how did you sleep")
    }

    @Test("a word two passes agreed on doesn't flip under the reader's eyes, while the end of the line keeps moving")
    func heldWordStays() {
        var agreement = LiveAgreement()
        _ = agreement.settle("I have an appointment at the")
        _ = agreement.settle("I have an appointment at the doctor")
        let shown = agreement.settle("I have an ointment at the doctor at ten")
        #expect(shown == "I have an appointment at the doctor at ten")
    }

    @Test("a word read the new way twice in a row is a correction: two pills that became three shows three")
    func confirmedCorrection() {
        var agreement = LiveAgreement()
        _ = agreement.settle("take two pills after the meal")
        _ = agreement.settle("take two pills after the meal today")
        #expect(agreement.settle("take three pills after the meal today") == "take two pills after the meal today")
        #expect(agreement.settle("take three pills after the meal today please") == "take three pills after the meal today please")
        #expect(agreement.settle("take two pills after the meal today please now") == "take three pills after the meal today please now")
    }

    @Test("a word seen only once was never agreed on, so the newer reading is shown")
    func notYetAgreed() {
        var agreement = LiveAgreement()
        _ = agreement.settle("I have an ointment")
        #expect(agreement.settle("I have an appointment at") == "I have an appointment at")
    }

    @Test("a pass that rewrites much of the line is a real change of mind and is shown")
    func rewrite() {
        var agreement = LiveAgreement()
        _ = agreement.settle("the cat sat on the mat")
        _ = agreement.settle("the cat sat on the mat today")
        #expect(agreement.settle("a bat spat at a hat today and") == "a bat spat at a hat today and")
        #expect(agreement.settle("a bat spat at a hat today and then") == "a bat spat at a hat today and then")
    }

    @Test("a pass that drops words is shown, and what it dropped is no longer held")
    func shrinks() {
        var agreement = LiveAgreement()
        _ = agreement.settle("thank you thank you very much")
        _ = agreement.settle("thank you thank you very much indeed")
        #expect(agreement.settle("thank you very") == "thank you very")
        #expect(agreement.settle("thank you very much") == "thank you very much")
    }

    @Test("a word or two of an agreed line is held, but a third changed word is a real change of mind")
    func twoHeldThreeShown() {
        let line = "we will meet at the clinic on Sunday at ten"
        var agreement = LiveAgreement()
        _ = agreement.settle(line)
        _ = agreement.settle(line)
        #expect(agreement.settle("we will meet at the clinic on Monday at two") == line)
        var other = LiveAgreement()
        _ = other.settle(line)
        _ = other.settle(line)
        #expect(other.settle("we will eat at the clinic on Monday at two") == "we will eat at the clinic on Monday at two")
    }

    @Test("changed words are held only while they are at most a third of the agreed ones")
    func aThirdAtMost() {
        var agreement = LiveAgreement()
        _ = agreement.settle("one two three four five six")
        _ = agreement.settle("one two three four five six")
        #expect(agreement.settle("one two tree four five sticks") == "one two three four five six")
        var shorter = LiveAgreement()
        _ = shorter.settle("one two three four five")
        _ = shorter.settle("one two three four five")
        #expect(shorter.settle("one two tree four fives") == "one two tree four fives")
    }

    @Test("one changed word in a two-word agreed line is too much of it to hold, so the new reading is shown")
    func shortLineChange() {
        var agreement = LiveAgreement()
        _ = agreement.settle("good morning")
        _ = agreement.settle("good morning")
        #expect(agreement.settle("good evening") == "good evening")
        #expect(agreement.settle("good evening everyone") == "good evening everyone")
    }

    @Test("an empty pass changes nothing")
    func empty() {
        var agreement = LiveAgreement()
        _ = agreement.settle("hello there")
        #expect(agreement.settle("  ") == "  ")
        #expect(agreement.settle("hello there friend") == "hello there friend")
    }

    @Test("on the caption line itself: held while it is being written, and the final pass always wins")
    func throughTheStabilizer() {
        var stabilizer = CaptionStabilizer()
        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "I have an appointment at the", isFinal: false, timestamp: 1))
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "I have an appointment at the doctor", isFinal: false, timestamp: 2))
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "I have an ointment at the doctor at ten", isFinal: false, timestamp: 3))
        #expect(stabilizer.segments[0].text == "I have an appointment at the doctor at ten")

        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "I have an ointment at the doctor at ten.", isFinal: true, timestamp: 4))
        #expect(stabilizer.segments[0].text == "I have an ointment at the doctor at ten.")
    }

    @Test("lines whose final never came are forgotten, and the line being written keeps its held words")
    func abandonedLinesAreForgotten() {
        var stabilizer = CaptionStabilizer()
        let abandoned = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: abandoned, text: "take two pills after the meal", isFinal: false, timestamp: 1))
        stabilizer.ingest(TranscriptToken(utteranceID: abandoned, text: "take two pills after the meal today", isFinal: false, timestamp: 2))
        for (offset, text) in ["good morning", "how are you", "fine thanks"].enumerated() {
            stabilizer.ingest(TranscriptToken(utteranceID: UUID(), text: text, isFinal: false, timestamp: 3 + Double(offset)))
        }

        let id = UUID()
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "I have an appointment at the", isFinal: false, timestamp: 6))
        stabilizer.ingest(TranscriptToken(utteranceID: id, text: "I have an appointment at the doctor", isFinal: false, timestamp: 7))
        let line = stabilizer.ingest(TranscriptToken(utteranceID: id, text: "I have an ointment at the doctor at ten", isFinal: false, timestamp: 8))
        #expect(line.text == "I have an appointment at the doctor at ten")

        let forgotten = stabilizer.ingest(TranscriptToken(utteranceID: abandoned, text: "take three pills after the meal today", isFinal: false, timestamp: 9))
        #expect(forgotten.text == "take three pills after the meal today")
    }
}
