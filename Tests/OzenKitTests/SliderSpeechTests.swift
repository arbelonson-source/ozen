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
        #expect(SliderSpeech.textSize(34, in: .english) == "Points: 34")
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

    @Test("a rate the slider stores as a Float keeps its band")
    func floatRateKeepsBand() {
        let expected: [(Float, String)] = [(0.2, "Slow"), (0.35, "Slow"), (0.4, "Normal"), (0.5, "Normal"), (0.55, "Normal"), (0.6, "Fast"), (0.7, "Fast")]
        for (stored, word) in expected {
            let spoken = SliderSpeech.speechRate(Double(stored), in: .english)
            #expect(spoken.hasPrefix(word + ","), "\(stored) spoke \(spoken)")
        }
    }

    @Test("languages that inflect the unit by number say the unit first")
    func textSizeUnitFirstWhereItInflects() {
        for language in [UILanguage.russian, .ukrainian, .arabic] {
            let sizes: [Double] = [1, 2, 4, 5, 11, 12, 14, 21, 22, 25]
            for size in sizes {
                let spoken = SliderSpeech.textSize(size, in: language)
                #expect(spoken.hasSuffix(" \(Int(size))"), "\(language) spoke \(spoken)")
            }
        }
    }
}
