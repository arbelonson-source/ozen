import Foundation
import Testing
@testable import OzenKit

@Suite("KeywordPill")
struct KeywordPillTests {
    private func match(_ text: String) -> KeywordMatch {
        KeywordMatch(alertID: UUID(), phrase: text, matchedText: text, wordIndex: 0)
    }

    @Test("normal text size keeps the base time however long the phrase is")
    func baseCase() {
        #expect(KeywordPill.visibleSeconds(characterCount: 10, accessibilitySize: false) == 5)
        #expect(KeywordPill.visibleSeconds(characterCount: 200, accessibilitySize: false) == 5)
        #expect(KeywordPill.visibleSeconds(characterCount: 0, accessibilitySize: true) == 5)
    }

    @Test("a long phrase at an accessibility size stays longer, within a cap")
    func longPhraseStaysLonger() {
        let short = KeywordPill.visibleSeconds(characterCount: 20, accessibilitySize: true)
        let long = KeywordPill.visibleSeconds(characterCount: 60, accessibilitySize: true)
        #expect(short > 5)
        #expect(long > short)
        #expect(KeywordPill.visibleSeconds(characterCount: 5000, accessibilitySize: true) <= 20)
    }

    @Test("the text names the speaker when one is known")
    func speakerNamed() {
        let withSpeaker = KeywordPill.text(said: match("Doctor"), speaker: "Dana", in: .english)
        #expect(withSpeaker.contains("Dana"))
        #expect(withSpeaker.contains("Doctor"))
        #expect(KeywordPill.text(said: match("Doctor"), speaker: nil, in: .english) == "Said: Doctor")
    }

    @Test("the Hebrew text carries no gendered verb")
    func hebrewDash() {
        #expect(KeywordPill.text(said: match("רופא"), speaker: "דנה", in: .hebrew) == "דנה — נאמר: רופא")
    }

    @Test("time left counts down from the first showing and never exceeds the total")
    func timeLeft() {
        #expect(KeywordPill.secondsLeft(total: 5, elapsed: 0) == 5)
        #expect(KeywordPill.secondsLeft(total: 5, elapsed: 3) == 2)
        #expect(KeywordPill.secondsLeft(total: 5, elapsed: 9) == 0)
        #expect(KeywordPill.secondsLeft(total: 5, elapsed: -4) == 5)
    }

    @Test("a longer total after a text size change only adds the extra time")
    func timeLeftAfterGrowth() {
        #expect(KeywordPill.secondsLeft(total: 12, elapsed: 4) == 8)
        #expect(KeywordPill.secondsLeft(total: 12, elapsed: 4) > KeywordPill.secondsLeft(total: 5, elapsed: 4))
    }
}
