import Testing
@testable import OzenKit

@Suite("The language a recording is listened to in")
struct RecordingLanguageTests {
    @Test("every language gets the same code live captions would give their engine")
    func sameAsLiveCaptions() {
        for language in UILanguage.allCases {
            var settings = AppSettings.default
            settings.spokenLanguage = language
            #expect(language.engineLanguageCode == settings.languageCode, "\(language)")
            #expect(UILanguage.match(languageTag: language.engineLanguageCode) == language, "\(language)")
        }
    }

    @Test("Hebrew and English are two letters, and Chinese is written without its script")
    func knownCodes() {
        #expect(UILanguage.hebrew.engineLanguageCode == "he")
        #expect(UILanguage.english.engineLanguageCode == "en")
        #expect(UILanguage.chineseSimplified.engineLanguageCode == "zh")
    }
}
