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

    @Test("speaker separation is spoken as the way it leans, then its number")
    func speakerSeparationWords() {
        let usual = Double(AppSettings.default.speakerSimilarityThreshold)
        #expect(SliderSpeech.speakerSeparation(0.3, in: .english) == "More merging, 0.30")
        #expect(SliderSpeech.speakerSeparation(usual, in: .english) == "Normal, 0.45")
        #expect(SliderSpeech.speakerSeparation(0.8, in: .english) == "More separating, 0.80")
        #expect(SliderSpeech.speakerSeparation(0.3, in: .hebrew) != SliderSpeech.speakerSeparation(0.3, in: .english))
    }

    @Test("a separation value saved between steps by an older version keeps its own number")
    func speakerSeparationOldValue() {
        #expect(SliderSpeech.speakerSeparation(0.47, in: .english) == "More separating, 0.47")
        #expect(SliderSpeech.speakerSeparation(0.42, in: .english) == "More merging, 0.42")
    }

    @Test("the separation slider moves in few enough steps to swipe through, and the usual value is one of them")
    func speakerSeparationSteps() {
        let range = SliderSpeech.speakerSeparationRange
        let step = SliderSpeech.speakerSeparationStep
        let steps = Double((range.upperBound - range.lowerBound) / step)
        #expect(abs(steps - steps.rounded()) < 0.001)
        #expect(steps.rounded() <= 20, "\(Int(steps.rounded())) swipes from end to end")
        let toUsual = Double((AppSettings.default.speakerSimilarityThreshold - range.lowerBound) / step)
        #expect(abs(toUsual - toUsual.rounded()) < 0.001)
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
