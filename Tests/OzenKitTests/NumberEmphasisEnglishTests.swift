import Foundation
import Testing
@testable import OzenKit

@Suite("English number words standing out in captions")
struct NumberEmphasisEnglishTests {
    private func emphasized(_ text: String, _ language: String = "en") -> [String] {
        NumberEmphasis.ranges(in: text, languageCode: language).map { String(text[$0]) }
    }

    @Test("pills and a clock time said in words")
    func pillsAtATime() {
        #expect(emphasized("Take three pills at ten thirty") == ["three pills", "ten thirty"])
        #expect(emphasized("Take one pill at five o'clock") == ["one pill", "five o'clock"])
        #expect(emphasized("See you at ten thirty pm.") == ["ten thirty pm"])
    }

    @Test("how often: a number of times a day, once or twice a day")
    func timesADay() {
        #expect(emphasized("Take it twice a day") == ["twice a day"])
        #expect(emphasized("Take it once a day with food") == ["once a day"])
        #expect(emphasized("Three times a day after meals") == ["Three times a day"])
        #expect(emphasized("Take one pill twice a week") == ["one pill", "twice a week"])
    }

    @Test("every N hours stands out with its hours")
    func everyHours() {
        #expect(emphasized("Every eight hours") == ["eight hours"])
        #expect(emphasized("Come back in two weeks") == ["two weeks"])
    }

    @Test("a half or a quarter of something, and the clock phrases")
    func fractions() {
        #expect(emphasized("Take half a tablet") == ["half a tablet"])
        #expect(emphasized("Three quarters of a cup") == ["Three quarters of a cup"])
        #expect(emphasized("It is quarter past six") == ["quarter past six"])
    }

    @Test("compound numbers and amounts")
    func amounts() {
        #expect(emphasized("Two hundred milligrams") == ["Two hundred milligrams"])
        #expect(emphasized("It costs one hundred and fifty dollars") == ["one hundred and fifty dollars"])
        #expect(emphasized("The patient is twenty-three") == ["twenty-three"])
        #expect(emphasized("Take 3 pills at 10:30") == ["3 pills", "10:30"])
    }

    @Test("\"one\" as a pronoun, and words that are not quantities here, stay plain")
    func notQuantities() {
        #expect(emphasized("The one I like is the red one") == [])
        #expect(emphasized("Which one is it") == [])
        #expect(emphasized("No one came, but one of the doctors called") == [])
        #expect(emphasized("Once I get there I will call") == [])
        #expect(emphasized("The first half was fine") == [])
        #expect(emphasized("Half of the people left") == [])
        #expect(emphasized("They are one on one") == [])
    }

    @Test("punctuation ends an amount before the next word")
    func punctuation() {
        #expect(emphasized("At ten, pills are due") == ["ten"])
    }

    @Test("each language has its own word list; Hebrew and languages with no list are unchanged, Hebrew words still count in an English line")
    func perLanguage() {
        #expect(emphasized("Take three pills", "he") == [])
        #expect(emphasized("prenez trois comprimés", "fr") == [])
        #expect(emphasized("Take 3 pills", "fr") == ["3"])
        #expect(emphasized("שלושה כדורים בעשר", "he") == ["שלושה כדורים", "בעשר"])
        #expect(emphasized("Take 3 pills, שלושה כדורים", "en") == ["3 pills", "שלושה כדורים"])
        #expect(emphasized("Take 3 pills", "en-US") == ["3 pills"])
    }

    @Test("a line is listed under numbers said by the same rules")
    func listable() {
        #expect(NumberEmphasis.hasListableNumber("Take three pills", languageCode: "en"))
        #expect(NumberEmphasis.hasListableNumber("Twice a day", languageCode: "en"))
        #expect(!NumberEmphasis.hasListableNumber("No one came", languageCode: "en"))
        #expect(!NumberEmphasis.hasListableNumber("I took two", languageCode: "en"))
        #expect(!NumberEmphasis.hasListableNumber("Take three pills", languageCode: "he"))
    }

    @Test("an English amount and what it counts are kept on one line when drawn")
    func keptTogether() {
        #expect(CaptionLayout.displayText("Take three pills at nine", languageCode: "en") == "Take three\u{00A0}pills at nine")
        #expect(CaptionLayout.displayText("Take three pills at nine", languageCode: "he") == "Take three pills at nine")
    }

    @Test("once every two days keeps the counted period")
    func onceEveryTwoDays() {
        #expect(emphasized("Take it once every two days") == ["once every two days"])
    }

    @Test("one and a half is one amount")
    func mixedFraction() {
        #expect(emphasized("Take one and a half tablets") == ["one and a half tablets"])
    }

    @Test("half past a number written in digits")
    func halfPastDigits() {
        #expect(emphasized("Meet at half past 3") == ["half past 3"])
    }
}
