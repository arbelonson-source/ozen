import Testing
@testable import OzenKit

@Suite("Progress of a recording being turned into text")
struct TranscriptionProgressTests {
    @Test("the fraction is how much of the recording has been heard")
    func fraction() {
        #expect(TranscriptionProgress.fraction(doneSeconds: 30, totalSeconds: 120) == 0.25)
        #expect(TranscriptionProgress.fraction(doneSeconds: 0, totalSeconds: 120) == 0)
    }

    @Test("the fraction never leaves zero to one")
    func clamped() {
        #expect(TranscriptionProgress.fraction(doneSeconds: 130, totalSeconds: 120) == 1)
        #expect(TranscriptionProgress.fraction(doneSeconds: -3, totalSeconds: 120) == 0)
    }

    @Test("the fraction is unknown when the length is")
    func unknown() {
        #expect(TranscriptionProgress.fraction(doneSeconds: 30, totalSeconds: nil) == nil)
        #expect(TranscriptionProgress.fraction(doneSeconds: 30, totalSeconds: 0) == nil)
    }

    @Test("the percent rounds down so it never says done early")
    func percent() {
        #expect(TranscriptionProgress.percent(0.004) == 0)
        #expect(TranscriptionProgress.percent(0.9999) == 99)
        #expect(TranscriptionProgress.percent(0.99999905) == 99)
        #expect(TranscriptionProgress.percent(0.29) == 29)
        #expect(TranscriptionProgress.percent(0.42) == 42)
        #expect(TranscriptionProgress.percent(1) == 100)
        #expect(TranscriptionProgress.percent(7) == 100)
        #expect(TranscriptionProgress.percent(-1) == 0)
    }

    @Test("the time left scales the time so far by what is still to come")
    func timeLeft() throws {
        let left = try #require(TranscriptionProgress.secondsLeft(elapsedSeconds: 100, doneSeconds: 60, totalSeconds: 240))
        #expect(abs(left - 300) < 1e-9)
    }

    @Test("the time left is unknown until there is something to go on")
    func timeLeftUnknown() {
        #expect(TranscriptionProgress.secondsLeft(elapsedSeconds: 2, doneSeconds: 30, totalSeconds: 240) == nil)
        #expect(TranscriptionProgress.secondsLeft(elapsedSeconds: 30, doneSeconds: 0.5, totalSeconds: 240) == nil)
        #expect(TranscriptionProgress.secondsLeft(elapsedSeconds: 30, doneSeconds: 30, totalSeconds: nil) == nil)
    }

    @Test("nothing is left once everything is heard")
    func allHeard() {
        #expect(TranscriptionProgress.secondsLeft(elapsedSeconds: 50, doneSeconds: 240, totalSeconds: 240) == 0)
    }

    @Test("the time left is said roughly, in English")
    func english() {
        Localization.$override.withValue(.english) {
            #expect(TranscriptionProgress.timeLeftText(40) == "Less than a minute left")
            #expect(TranscriptionProgress.timeLeftText(61) == "About 1 min left")
            #expect(TranscriptionProgress.timeLeftText(7 * 60 + 10) == "About 7 min left")
            #expect(TranscriptionProgress.timeLeftText(3_900) == "About 1 h 5 min left")
            #expect(TranscriptionProgress.timeLeftText(7_200) == "About 2 h left")
        }
    }

    @Test("the time left is said in Hebrew too")
    func hebrew() {
        Localization.$override.withValue(.hebrew) {
            #expect(TranscriptionProgress.timeLeftText(7 * 60).contains("7"))
            #expect(!TranscriptionProgress.timeLeftText(7 * 60).contains("min"))
        }
    }
}
