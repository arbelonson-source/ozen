import Foundation
import Testing
@testable import OzenKit

@Suite("CaptionSpeechLanguage")
struct CaptionSpeechLanguageTests {
    @Test("Hebrew letters are Hebrew whatever the setting says")
    func hebrewWins() {
        #expect(CaptionSpeechLanguage.bcp47(forLine: "שלום מה נשמע", languageCode: "en") == "he")
        #expect(CaptionSpeechLanguage.bcp47(forLine: "hello שלום", languageCode: "en") == "he")
    }

    @Test("a Latin-only line under a Hebrew setting is English")
    func latinUnderHebrew() {
        #expect(CaptionSpeechLanguage.bcp47(forLine: "hello", languageCode: "he") == "en")
    }

    @Test("a line with no letters follows the setting")
    func noLetters() {
        #expect(CaptionSpeechLanguage.bcp47(forLine: "12:30", languageCode: "he") == "he")
        #expect(CaptionSpeechLanguage.bcp47(forLine: "", languageCode: "fr") == "fr")
    }

    @Test("a Latin line under a Latin-script setting keeps the setting, full tags included")
    func latinKeepsSetting() {
        #expect(CaptionSpeechLanguage.bcp47(forLine: "Bonjour", languageCode: "fr") == "fr")
        #expect(CaptionSpeechLanguage.bcp47(forLine: "Olá, tudo bem", languageCode: "pt-BR") == "pt-BR")
    }

    @Test("a Latin-only line under an Amharic setting is English, not read with the Amharic voice")
    func latinUnderAmharic() {
        #expect(CaptionSpeechLanguage.bcp47(forLine: "hello", languageCode: "am") == "en")
        #expect(CaptionSpeechLanguage.bcp47(forLine: "hello", languageCode: "am-ET") == "en")
    }
}
