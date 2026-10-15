import Testing
@testable import OzenKit

@Suite("SliderSpeech")
struct SliderSpeechTests {
    @Test("the speech rate is spoken as a word and a number")
    func speechRateWords() {
        #expect(SliderSpeech.speechRate(0.2, in: .english) == "Slow, 0.20")
        #expect(SliderSpeech.speechRate(0.45, in: .english) == "Normal, 0.45")
        #expect(SliderSpeech.speechRate(0.7, in: .english) == "Fast, 0.70")
    }

    @Test("the text size is spoken with its unit")
    func textSizeUnit() {
        #expect(SliderSpeech.textSize(34, in: .english) == "34 points")
    }

    @Test("Hebrew and English differ and the hints tell which way is which")
    func hebrewDiffers() {
        for rate in [0.2, 0.45, 0.7] {
            #expect(SliderSpeech.speechRate(rate, in: .hebrew) != SliderSpeech.speechRate(rate, in: .english))
        }
        #expect(SliderSpeech.textSize(34, in: .hebrew) != SliderSpeech.textSize(34, in: .english))
        #expect(SliderSpeech.speechRateHint(in: .hebrew) != SliderSpeech.speechRateHint(in: .english))
        #expect(SliderSpeech.textSizeHint(in: .english) == "Swipe up for larger, down for smaller.")
    }
}
