import Foundation
import Testing
@testable import OzenKit

@Suite("Thanks said to an empty room")
struct SilencePhraseGuardTests {
    private func line(_ text: String, id: UUID = UUID(), final: Bool = true, voiced: Int? = nil, at time: TimeInterval) -> (TranscriptToken, TimeInterval) {
        (TranscriptToken(utteranceID: id, text: text, isFinal: final, timestamp: time, voicedChunks: voiced), time)
    }

    private func admitted(_ lines: [(TranscriptToken, TimeInterval)]) -> [Bool] {
        var guardian = SilencePhraseGuard()
        return lines.map { guardian.admits($0.0, at: $0.1) }
    }

    @Test("thanks repeated within one line is never shown")
    func repeatedWithinLine() {
        #expect(admitted([
            line("תודה. תודה. תודה.", at: 10),
            line("תודה רבה, תודה רבה", at: 200),
            line("Thank you. Thank you.", at: 400),
        ]) == [false, false, false])
    }

    @Test("a lone thanks shows once; the same again within seconds with nothing said between does not, and a run of them stays hidden")
    func loneThanksComesBack() {
        let first = UUID()
        #expect(admitted([
            line("תודה.", id: first, final: false, at: 0),
            line("תודה.", id: first, at: 1),
            line(" ... ", at: 5),
            line("תודה.", at: 12),
            line("תודה רבה", at: 24),
            line("תודה.", at: 36),
            line("תודה.", at: 60),
        ]) == [true, true, true, false, false, false, true])
    }

    @Test("two real thank-yous half a minute apart both show")
    func twoRealThanks() {
        #expect(admitted([
            line("תודה", at: 0),
            line("תודה רבה", at: 30),
        ]) == [true, true])
    }

    @Test("thanks with a name that is also a sound tag is someone talking, so it shows; a line of only sign-offs or only tags still doesn't")
    func thanksToShira() {
        #expect(admitted([
            line("תודה שירה", at: 0),
            line("שירה, תודה רבה!", at: 100),
            line("מוזיקה. שירה.", at: 200),
            line("תודה רבה, צפייה מהנה", at: 300),
        ]) == [true, true, false, false])
    }

    @Test("real words in between, or grown into a sentence, show as usual")
    func realSpeech() {
        let growing = UUID()
        #expect(admitted([
            line("תודה", at: 0),
            line("בבקשה, אין על מה", at: 5),
            line("תודה", at: 8),
            line("תודה", id: growing, final: false, at: 9),
            line("תודה רבה על הארוחה", id: growing, at: 10),
            line("תודה תודה לסבתא", at: 11),
        ]) == [true, true, true, false, true, true])
    }

    @Test("the same thanks said twice or three times in one clearly voiced breath shows")
    func voicedRepeatShows() {
        #expect(admitted([line("תודה, תודה", voiced: 5, at: 0)]) == [true])
        #expect(admitted([line("Thank you, thank you", voiced: 5, at: 0)]) == [true])
        #expect(admitted([line("תודה. תודה. תודה.", voiced: 6, at: 0)]) == [true])
    }

    @Test("a repeated thanks with no voice count, or under two voiced chunks per thanks, stays hidden")
    func weaklyVoicedRepeatHidden() {
        #expect(admitted([line("תודה, תודה", at: 0)]) == [false])
        #expect(admitted([line("תודה, תודה", voiced: 3, at: 0)]) == [false])
        #expect(admitted([line("תודה. תודה. תודה.", voiced: 5, at: 0)]) == [false])
    }

    @Test("sound tags and mixed sign-offs stay hidden however much voice was heard")
    func voicedTagsAndSignOffsHidden() {
        #expect(admitted([
            line("שירה, שירה", voiced: 10, at: 0),
            line("מוזיקה. מוזיקה.", voiced: 10, at: 100),
            line("תודה רבה, צפייה מהנה", voiced: 10, at: 200),
        ]) == [false, false, false])
    }

    @Test("a voiced repeat seconds after a shown thanks, or a lone thanks seconds after a shown repeat, is still a thanks coming back")
    func voicedRepeatStillComesBack() {
        #expect(admitted([
            line("תודה", at: 0),
            line("תודה, תודה", voiced: 5, at: 5),
        ]) == [true, false])
        #expect(admitted([
            line("תודה, תודה", voiced: 5, at: 0),
            line("תודה", at: 5),
        ]) == [true, false])
    }

    @Test("a shown thanks that grows into a clearly voiced repeat on the same line keeps showing")
    func shownThanksGrowsIntoVoicedRepeat() {
        let growing = UUID()
        #expect(admitted([
            line("תודה", id: growing, final: false, voiced: 2, at: 0),
            line("תודה, תודה", id: growing, voiced: 5, at: 1),
        ]) == [true, true])
    }

    @Test("a repeated thanks first decoded with too little voice shows once a later decode of the same line hears enough")
    func voicedRepeatWaitsForVoice() {
        let growing = UUID()
        #expect(admitted([
            line("תודה, תודה", id: growing, final: false, voiced: 2, at: 0),
            line("תודה, תודה", id: growing, voiced: 5, at: 1),
            line("תודה", at: 4),
        ]) == [false, true, false])
    }

    @Test("a repeated thanks hidden as coming back stays hidden on that line however much voice follows")
    func comingBackStaysHidden() {
        let repeatLine = UUID()
        #expect(admitted([
            line("תודה", at: 0),
            line("תודה, תודה", id: repeatLine, final: false, voiced: 2, at: 5),
            line("תודה, תודה", id: repeatLine, voiced: 5, at: 6),
        ]) == [true, false, false])
    }

    @Test("thanks and thank-you-very-much in one clearly voiced breath shows, with too little voice it stays hidden")
    func thanksVariantsInOneBreath() {
        #expect(admitted([line("תודה, תודה רבה", voiced: 4, at: 0)]) == [true])
        #expect(admitted([line("תודה רבה, תודה לכם", voiced: 4, at: 0)]) == [true])
        #expect(admitted([line("תודה, תודה רבה", voiced: 3, at: 0)]) == [false])
    }
}
