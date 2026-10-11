import Testing
@testable import OzenKit

@Suite("The spoken language")
struct SpokenLanguageTests {
    @Test("Hebrew until she chooses, as before there was a choice")
    func hebrewUntilChosen() {
        #expect(AppSettings.default.spokenLanguage == .hebrew)
    }

    @Test("each language chosen reaches the engines as Whisper's code for it, and reads back as chosen")
    func choosingGivesWhispersCode() {
        let codes: [UILanguage: String] = [
            .hebrew: "he", .english: "en", .arabic: "ar", .russian: "ru", .amharic: "am", .french: "fr",
            .spanish: "es", .ukrainian: "uk", .german: "de", .portuguese: "pt", .chineseSimplified: "zh", .hindi: "hi",
        ]
        #expect(Set(codes.keys) == Set(UILanguage.allCases))
        for (language, code) in codes {
            var settings = AppSettings.default
            settings.spokenLanguage = language
            #expect(settings.languageCode == code)
            #expect(settings.spokenLanguage == language)
        }
    }

    @Test("each is named as it is spoken: Chinese as 中文, not by the simplified writing the menus name, the rest as in the menus")
    func names() {
        #expect(UILanguage.chineseSimplified.spokenName == "中文")
        for language in UILanguage.allCases where language != .chineseSimplified {
            #expect(language.spokenName == language.nativeName)
        }
    }

    @Test("a saved code with a region reads as its language; one no listed language has, or Hebrew's old code, reads as Hebrew")
    func savedCodes() {
        var settings = AppSettings.default
        settings.languageCode = "en-GB"
        #expect(settings.spokenLanguage == .english)
        settings.languageCode = "xx"
        #expect(settings.spokenLanguage == .hebrew)
        settings.languageCode = "iw"
        #expect(settings.spokenLanguage == .hebrew)
    }
}
