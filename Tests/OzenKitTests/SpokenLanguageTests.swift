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

    @Test("Hebrew keeps Ozen's Hebrew model; every other language is recommended the general Turbo, which isn't trained for Hebrew alone")
    func recommendedModel() {
        #expect(WhisperModelCatalog.recommendedVariant(for: .hebrew) == WhisperModelCatalog.recommendedVariant)
        for language in UILanguage.allCases where language != .hebrew {
            #expect(WhisperModelCatalog.recommendedVariant(for: language) == "large-v3-v20240930_626MB")
        }
        #expect(WhisperModelCatalog.option(for: "large-v3-v20240930_626MB")?.isHebrewOnly == false)
        #expect(WhisperModelCatalog.options.filter(\.isHebrewOnly).map(\.variant) == ["ozen-turbo-hebrew-a3-8bit", "ivrit-large-v3-turbo-8bit"])
    }

    @Test("for any other language a model trained for Hebrew alone is the weaker one, and the general ones are left as chosen")
    func weakerModel() {
        var settings = AppSettings.default
        settings.spokenLanguage = .english
        #expect(settings.recommendedWhisperVariant == "large-v3-v20240930_626MB")
        #expect(settings.runsWeakerModel)
        settings.whisperModelVariant = "ivrit-large-v3-turbo-8bit"
        #expect(settings.runsWeakerModel)
        for general in ["large-v3-v20240930_626MB", "small", "large-v3"] {
            settings.whisperModelVariant = general
            #expect(!settings.runsWeakerModel, "\(general)")
        }
        settings.spokenLanguage = .hebrew
        #expect(settings.recommendedWhisperVariant == WhisperModelCatalog.recommendedVariant)
        settings.whisperModelVariant = "small"
        #expect(settings.runsWeakerModel)
        settings.whisperModelVariant = WhisperModelCatalog.recommendedVariant
        #expect(!settings.runsWeakerModel)
    }

    @Test("the model screen lists the language's recommended model first, the other general ones next, and for any language but Hebrew the Hebrew-only ones last")
    func listedOrder() {
        #expect(WhisperModelCatalog.listed(for: .hebrew) == WhisperModelCatalog.listed)
        let english = WhisperModelCatalog.listed(for: .english).map(\.variant)
        #expect(english.first == WhisperModelCatalog.generalVariant)
        #expect(Array(english.suffix(2)) == ["ozen-turbo-hebrew-a3-8bit", "ivrit-large-v3-turbo-8bit"])
        #expect(english.sorted() == WhisperModelCatalog.options.map(\.variant).sorted())
    }

    @Test("the suggested names and words are offered as said in the spoken language: Hebrew's own, or what each means in it")
    func suggestionsAsSaid() {
        #expect(AlertSuggestions.offeredNames(spoken: .hebrew) == AlertSuggestions.names)
        #expect(AlertSuggestions.offeredWords(spoken: .hebrew) == AlertSuggestions.words)
        #expect(AlertSuggestions.offeredNames(spoken: .english) == ["Grandma", "Mom"])
        #expect(AlertSuggestions.offeredWords(spoken: .english) == ["Grandma", "Mom", "Medicine", "Doctor"])
        #expect(AlertSuggestions.offeredNames(spoken: .russian) == ["Бабушка", "Мама"])
    }
}
