import Foundation
import Testing
@testable import OzenKit

@Suite("Localization")
struct LocalizationTests {
    @Test("an engine's name reads in the app's language, as Settings names it, wherever a saved conversation shows it")
    func engineNames() {
        #expect(TranscriptionEngineKind.homeServer.displayName(in: .hebrew) == "המחשב בבית")
        #expect(TranscriptionEngineKind.whisperKit.displayName(in: .english) == "Whisper (on device)")
        #expect(TranscriptionEngineKind.appleSpeech.displayName(in: .russian) == "Распознавание речи Apple")
        for kind in TranscriptionEngineKind.allCases {
            #expect(kind.displayName(in: .hebrew) != kind.displayName(in: .english), "\(kind)")
        }
    }

    @Test("English counts one second and one hour in the singular, more in the plural")
    func englishTimeWords() {
        #expect(TimeUnitWord.second(.one, in: .english) == "second" && TimeUnitWord.second(.other, in: .english) == "seconds")
        #expect(TimeUnitWord.hour(.one, in: .english) == "hour" && TimeUnitWord.hour(.other, in: .english) == "hours")
        #expect(TimeUnitWord.minute(.one, in: .english) == "minute" && TimeUnitWord.minute(.other, in: .english) == "minutes")
    }

    @Test("the phone's preferred languages resolve to the first one that's supported; Hebrew is the fallback")
    func resolution() {
        #expect(AppLanguage.system.resolved(preferredLanguages: ["he-IL", "en-US"]) == .hebrew)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["iw"]) == .hebrew)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["en-GB"]) == .english)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["ru-RU", "he-IL"]) == .russian)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["ar-EG"]) == .arabic)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["fr-CA", "en-US"]) == .french)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["zh-Hans-US", "en-US"]) == .chineseSimplified)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["zh-Hant-TW"]) == .chineseSimplified)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["am-ET", "en-US"]) == .amharic)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["es-MX", "en-US"]) == .spanish)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["uk-UA", "ru-RU"]) == .ukrainian)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["pt-BR", "en-US"]) == .portuguese)
        #expect(AppLanguage.system.resolved(preferredLanguages: ["hi-IN", "en-US"]) == .hindi)
        // A preference nobody supports is skipped in favor of the next one...
        #expect(AppLanguage.system.resolved(preferredLanguages: ["da-DK", "de-DE"]) == .german)
        // ...and falls back to Hebrew when none of them are supported at all.
        #expect(AppLanguage.system.resolved(preferredLanguages: ["da-DK"]) == .hebrew)
        #expect(AppLanguage.system.resolved(preferredLanguages: []) == .hebrew)
        #expect(AppLanguage.hebrew.resolved(preferredLanguages: ["en-US"]) == .hebrew)
        #expect(AppLanguage.english.resolved(preferredLanguages: ["he-IL"]) == .english)
        #expect(AppLanguage.arabic.resolved(preferredLanguages: ["en-US"]) == .arabic)
        #expect(AppLanguage.hindi.resolved(preferredLanguages: []) == .hindi)
    }

    @Test("tr gives the version for the language in use")
    func pick() {
        #expect(tr("שלום", "Hello", in: .hebrew) == "שלום")
        #expect(tr("שלום", "Hello", in: .english) == "Hello")
        let english = Localization.$override.withValue(.english) { tr("שלום", "Hello") }
        #expect(english == "Hello")
    }

    @Test("text put into a placeholder is kept as it is, even when it holds a placeholder itself")
    func insertedTextIsNotRescanned() {
        #expect(tr("%1 בשעה %2", "%1 at %2", args: ["Today", "50%1"], in: .english) == "Today at 50%1")
        #expect(tr("%1 בשעה %2", "%1 at %2", args: ["%2", "22:30"], in: .english) == "%2 at 22:30")
        #expect(tr("%1 בשעה %2", "%1 at %2", args: ["Today", "50%1"], in: .spanish) == "Today a las 50%1")
        #expect(tr("%1 בשעה %2", "%1 at %2", args: ["היום", "%1%2"], in: .hebrew) == "היום בשעה %1%2")
    }

    @Test("every language beyond Hebrew and English is looked up in the translation table")
    func translatedLanguages() {
        #expect(tr("ביטול", "Cancel", in: .arabic) == "إلغاء")
        #expect(tr("ביטול", "Cancel", in: .russian) == "Отмена")
        #expect(tr("ביטול", "Cancel", in: .french) == "Annuler")
        #expect(tr("ביטול", "Cancel", in: .german) == "Abbrechen")
        #expect(tr("ביטול", "Cancel", in: .spanish) == "Cancelar")
    }

    @Test("a key missing from a language's table falls back to English, never to an empty string")
    func fallbackToEnglish() {
        let notInAnyTable = "There is definitely no translation for this exact sentence anywhere"
        #expect(tr("אין תרגום כזה", notInAnyTable, in: .french) == notInAnyTable)
        #expect(tr("אין תרגום כזה", notInAnyTable, in: .hindi) == notInAnyTable)
        #expect(!tr("אין תרגום כזה", notInAnyTable, in: .amharic).isEmpty)
    }

    @Test("an interpolated string is translated as a template, with the values still substituted in")
    func templatedTranslation() {
        let french = tr("הסוללה ב־%1%", "Battery at %1%", args: ["42"], in: .french)
        #expect(french.contains("42"))
        #expect(french != "Battery at 42%")
        let english = tr("הסוללה ב־%1%", "Battery at %1%", args: ["42"], in: .english)
        #expect(english == "Battery at 42%")
        let hebrew = tr("הסוללה ב־%1%", "Battery at %1%", args: ["42"], in: .hebrew)
        #expect(hebrew == "הסוללה ב־42%")
    }

    @Test("a template used twice repeats the same value both times")
    func templatedTranslationRepeatsPlaceholder() {
        let result = tr("%1 מתוך %1, הכי איטי", "%1 of %1, slowest", args: ["7"], in: .english)
        #expect(result == "7 of 7, slowest")
    }

    @Test("a placeholder takes the longest number that has a value: %10 is the tenth with ten values, the first and a 0 with one")
    func longestPlaceholderNumberWins() {
        let ten = ["one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"]
        #expect(tr("%10 ו-%1", "%10 and %1", args: ten, in: .english) == "ten and one")
        #expect(tr("%10 דקות", "%10 minutes", args: ["2"], in: .english) == "20 minutes")
        #expect(tr("%1%", "%1%", args: ["42"], in: .english) == "42%")
        #expect(tr("%0 ו-%", "%0 and %", args: ["5"], in: .english) == "%0 and %")
    }

    @Test("a test's own language doesn't leak to others")
    func overrideIsScoped() async {
        await Localization.$override.withValue(.english) {
            await Task.yield()
            #expect(Localization.language == .english)
        }
        #expect(Localization.override == nil)
    }

    @Test("Hebrew and Arabic read right to left; every other language reads left to right")
    func direction() {
        #expect(UILanguage.hebrew.isRightToLeft)
        #expect(UILanguage.arabic.isRightToLeft)
        #expect(!UILanguage.english.isRightToLeft)
        for language in UILanguage.allCases where language != .hebrew && language != .arabic {
            #expect(!language.isRightToLeft, "\(language) should read left to right")
        }
    }

    // iOS sets a line's direction by its first letter. Settings showed
    // "VoiceOver מקריא שורות חדשות" with VoiceOver at the far left, the
    // last word a Hebrew reader reaches (ui-screenshots 37580885361).
    @Test("a right-to-left line that opens with an English word or a size stays right to left, so it reads in order")
    func rightToLeftLineOpeningInLatin() {
        let mark = "\u{200F}"
        #expect(tr("VoiceOver מקריא שורות חדשות", "VoiceOver reads new lines aloud", in: .hebrew) == mark + "VoiceOver מקריא שורות חדשות")
        #expect(tr("%1 בשימוש", "%1 in use", args: ["812 MB"], in: .hebrew) == mark + "812 MB בשימוש")
        #expect(tr("%1 לא זמין", "%1 isn't available", args: [TranscriptionEngineKind.whisperKit.displayName(in: .hebrew)], in: .hebrew) == mark + "Whisper (במכשיר) לא זמין")
        #expect(TranscriptionEngineKind.whisperKit.displayName(in: .arabic) == mark + "Whisper (على الجهاز)")
        #expect(tr(mark + "VoiceOver", "VoiceOver", in: .hebrew) == mark + "VoiceOver")
        #expect(tr("%1 מתוך %2", "%1 of %2", args: ["3", "5"], in: .hebrew) == "3 מתוך 5")
        #expect(tr("עוד %1", "%1 more", args: ["1.9 GB"], in: .hebrew) == "עוד 1.9 GB")
        for language in UILanguage.allCases where !language.isRightToLeft {
            #expect(!tr("VoiceOver מקריא שורות חדשות", "VoiceOver reads new lines aloud", in: language).hasPrefix(mark), "\(language)")
            #expect(!tr("%1 בשימוש", "%1 in use", args: ["812 MB"], in: language).hasPrefix(mark), "\(language)")
        }
    }

    @Test("a left-to-right line that opens with a Hebrew name or word stays left to right, so its English reads in order")
    func leftToRightLineOpeningInHebrew() {
        let mark = "\u{200E}"
        #expect(tr("%1 — נאמר: %2", "%1 said: %2", args: ["סבתא", "two pills"], in: .english) == mark + "סבתא said: two pills")
        #expect(tr("״%1״ כבר ברשימה.", "“%1” is already in the list.", args: ["דנה אברהמי"], in: .english) == mark + "“דנה אברהמי” is already in the list.")
        #expect(tr("%1 (%2 הקלטות)", "%1 (%2 recordings)", args: ["סבתא", "2"], in: .russian).hasPrefix(mark))
        #expect(tr(mark + "%1", mark + "%1", args: ["סבתא"], in: .english) == mark + "סבתא")
        #expect(tr("%1 — נאמר: %2", "%1 said: %2", args: ["Dana", "two pills"], in: .english) == "Dana said: two pills")
        #expect(tr("%1 — נאמר: %2", "%1 said: %2", args: ["סבתא", "שני כדורים"], in: .hebrew) == "סבתא — נאמר: שני כדורים")
        #expect(tr("%1 מתוך %2", "%1 of %2", args: ["3", "5"], in: .english) == "3 of 5")
    }

    // "שמתחילה ב־wss://" was drawn "//:wss": in a right-to-left sentence
    // the "://" took the sentence's direction (iPhone screen walk-through).
    @Test("an address in a right-to-left sentence is kept whole, left to right, so it reads the way it is typed")
    func addressReadsInOrder() {
        let (isolate, end) = ("\u{2066}", "\u{2069}")
        let outside = "Outside the home network, use the address that starts with wss://"
        #expect(tr("מחוץ לרשת הביתית צריך להשתמש בכתובת שמתחילה ב־wss://", outside, in: .hebrew)
            == "מחוץ לרשת הביתית צריך להשתמש בכתובת שמתחילה ב־" + isolate + "wss://" + end)
        #expect(tr("", outside + " · Tap for Settings", in: .arabic).contains(isolate + "wss://" + end + " · "))
        #expect(tr("כתובת שמתחילה ב־wss://. הקוד", "x", in: .hebrew) == "כתובת שמתחילה ב־" + isolate + "wss://" + end + ". הקוד")
        #expect(tr("ראו https://ozen.live/he/help, ואז", "x", in: .hebrew) == "ראו " + isolate + "https://ozen.live/he/help" + end + ", ואז")
        #expect(tr("שתי כתובות: ws:// ו־wss://", "x", in: .hebrew) == "שתי כתובות: " + isolate + "ws://" + end + " ו־" + isolate + "wss://" + end)
        #expect(tr("כבר " + isolate + "wss://" + end + " כאן", "x", in: .hebrew) == "כבר " + isolate + "wss://" + end + " כאן")
        #expect(tr("הכתובת: %1", "The address: %1", args: ["wss://a.example"], in: .hebrew) == "הכתובת: wss://a.example")
        #expect(tr("ב־%1, כמו wss://", "In %1, like wss://", args: ["Ozen"], in: .hebrew) == "ב־Ozen, כמו " + isolate + "wss://" + end)
        for language in UILanguage.allCases where !language.isRightToLeft {
            #expect(!tr("", outside, in: language).contains(isolate), "\(language)")
        }
    }

    @Test("the voice follows the letters, and the app's language when there are none")
    func speakingVoice() {
        #expect(UILanguage.forSpeaking("שלום, thanks", otherwise: .english) == .hebrew)
        #expect(UILanguage.forSpeaking("Thank you", otherwise: .hebrew) == .english)
        #expect(UILanguage.forSpeaking("10:30", otherwise: .hebrew) == .hebrew)
        #expect(UILanguage.forSpeaking("10:30", otherwise: .english) == .english)
        #expect(UILanguage.forSpeaking("3 × 4", otherwise: .hebrew) == .hebrew)
        #expect(UILanguage.forSpeaking("10 ÷ 2", otherwise: .hebrew) == .hebrew)
        #expect(UILanguage.forSpeaking("\u{FEFF}10:30", otherwise: .hebrew) == .hebrew)
        #expect(UILanguage.forSpeaking("Привет", otherwise: .hebrew) == .russian)
        #expect(UILanguage.forSpeaking("Дякую", otherwise: .ukrainian) == .ukrainian)
        #expect(UILanguage.forSpeaking("Я не зрозуміла", otherwise: .hebrew) == .ukrainian)
        #expect(UILanguage.forSpeaking("شكرًا", otherwise: .english) == .arabic)
        #expect(UILanguage.forSpeaking("አመሰግናለሁ", otherwise: .english) == .amharic)
        #expect(UILanguage.forSpeaking("धन्यवाद", otherwise: .english) == .hindi)
        #expect(UILanguage.forSpeaking("谢谢", otherwise: .english) == .chineseSimplified)
        #expect(UILanguage.forSpeaking("Merci", otherwise: .french) == .french)
        #expect(UILanguage.forSpeaking("Não", otherwise: .portuguese) == .portuguese)
        #expect(UILanguage.forSpeaking("Merci", otherwise: .arabic) == .english)
        #expect(UILanguage.forSpeaking("10:30", otherwise: .german) == .german)
    }

    @Test("every language's ready-made phrases are read by that language's voice")
    func quickPhrasesSpeakInTheirOwnLanguage() {
        for language in UILanguage.allCases {
            for phrase in AppSettings.defaultQuickPhrases(for: language) {
                #expect(UILanguage.forSpeaking(phrase, otherwise: language) == language, "\(language): \(phrase)")
            }
        }
    }

    @Test("text in a language the phone has no voice for is held back; Hebrew still follows the Hebrew voice")
    @MainActor
    func heldBackWithoutAVoice() {
        let synthesizer = FakeSynthesizer()
        #expect(synthesizer.canSay("שלום"))
        synthesizer.hasHebrewVoice = false
        #expect(!synthesizer.canSay("שלום"))
        #expect(synthesizer.canSay("Thank you"))
        #expect(synthesizer.canSay("شكرًا"))

        synthesizer.missingVoices = [.amharic]
        #expect(!synthesizer.canSay("አመሰግናለሁ"))
        #expect(synthesizer.canSay("شكرًا"))
        #expect(!synthesizer.hasVoiceOrOwnHint(for: .amharic))
        #expect(synthesizer.hasVoiceOrOwnHint(for: .arabic))
        #expect(synthesizer.hasVoiceOrOwnHint(for: .hebrew))
        #expect(synthesizer.hasVoiceOrOwnHint(for: .english))
    }

    @Test("dates are written in the app's language, not the phone's, keeping the phone's region")
    func dateLanguage() {
        let hebrew = UILanguage.hebrew.locale(keepingRegionOf: Locale(identifier: "en_GB"))
        #expect(hebrew.language.languageCode?.identifier == "he")
        #expect(hebrew.region?.identifier == "GB")
        let english = UILanguage.english.locale(keepingRegionOf: Locale(identifier: "he_IL"))
        #expect(english.language.languageCode?.identifier == "en")
        #expect(english.region?.identifier == "IL")

        let date = Date(timeIntervalSince1970: 1_791_000_000)
        let style = Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: TimeZone(identifier: "UTC")!)
        let inHebrew = date.formatted(style.locale(hebrew))
        let inEnglish = date.formatted(style.locale(english))
        #expect(inHebrew.unicodeScalars.contains { (0x05D0...0x05EA).contains($0.value) })
        #expect(!inEnglish.unicodeScalars.contains { (0x05D0...0x05EA).contains($0.value) })
        #expect(inEnglish.contains("Oct") || inEnglish.contains("Sep"))
    }

    @Test("every supported language formats a date in its own script or wording, keeping the phone's region")
    func dateLanguageForNewLanguages() {
        let base = Locale(identifier: "en_US")
        let date = Date(timeIntervalSince1970: 1_791_000_000)
        let style = Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: TimeZone(identifier: "UTC")!)
        for language in UILanguage.allCases {
            let locale = language.locale(keepingRegionOf: base)
            #expect(locale.region?.identifier == "US")
            let formatted = date.formatted(style.locale(locale))
            #expect(!formatted.isEmpty)
        }
        let chinese = UILanguage.chineseSimplified.locale(keepingRegionOf: base)
        #expect(chinese.language.languageCode?.identifier == "zh")
        #expect(chinese.language.script?.identifier == "Hans")
    }

    @Test("the Settings picker shows every language by its own name")
    func nativeNames() {
        #expect(UILanguage.hebrew.nativeName == "עברית")
        #expect(UILanguage.english.nativeName == "English")
        #expect(UILanguage.arabic.nativeName == "العربية")
        #expect(UILanguage.russian.nativeName == "Русский")
        #expect(UILanguage.amharic.nativeName == "አማርኛ")
        #expect(UILanguage.french.nativeName == "Français")
        #expect(UILanguage.spanish.nativeName == "Español")
        #expect(UILanguage.ukrainian.nativeName == "Українська")
        #expect(UILanguage.german.nativeName == "Deutsch")
        #expect(UILanguage.portuguese.nativeName == "Português")
        #expect(UILanguage.chineseSimplified.nativeName == "简体中文")
        #expect(UILanguage.hindi.nativeName == "हिन्दी")
    }

    @Test("Portuguese is European throughout, like the voice that reads it out")
    func portugueseIsEuropean() {
        #expect(UILanguage.portuguese.speechVoiceCode == "pt-PT")
        let table = TranslationTable.shared.table(for: .portuguese)
        #expect(!table.isEmpty)
        let brazilian: Set<String> = ["você", "vocês", "celular", "tela", "arquivo", "usuário", "baixar", "contato", "registrar"]
        for (english, portuguese) in table {
            let words = Set(portuguese.lowercased().split { !$0.isLetter }.map(String.init))
            for word in words.intersection(brazilian) {
                Issue.record("Brazilian '\(word)' in the Portuguese for: \(english)")
            }
        }
    }

    @Test("each language calls the home computer the same thing on every screen")
    func computerOneWordPerLanguage() {
        let words: [(UILanguage, used: String, not: String)] = [
            (.spanish, "ordenador", "computadora"),
            (.arabic, "كمبيوتر", "حاسوب"),
            (.amharic, "ኮምፒዩተ", "ኮምፒውተ"),
        ]
        for (language, used, not) in words {
            let table = TranslationTable.shared.table(for: language)
            #expect(table.values.contains { $0.contains(used) }, "\(language)")
            for (english, text) in table where text.lowercased().contains(not) {
                Issue.record("\(language): '\(not)' in the line for: \(english)")
            }
        }
    }

    @Test("Arabic speaks to the reader in the plural on every screen, as the Hebrew does, so no one is addressed as a man")
    func arabicAddressesEveryoneInThePlural() {
        let singular: Set<String> = [
            "اضغط", "المس", "انقر", "اختر", "افتح", "اكتب", "امسح", "انسخ", "الصقه", "تأكد", "أضف",
            "اسمح", "اتصل", "أعد", "حاول", "تبرّع", "يمكنك", "اسمك", "يوقظك", "يساعدك", "تفوتك",
        ]
        let table = TranslationTable.shared.table(for: .arabic)
        #expect(!table.isEmpty)
        for (english, arabic) in table where !english.hasPrefix("Hey Siri") {
            for word in arabic.split(whereSeparator: { !$0.isLetter }) {
                // "And" and "so" are written onto the next word: واضغط.
                let bare = word.first == "و" || word.first == "ف" ? String(word.dropFirst()) : String(word)
                if singular.contains(String(word)) || singular.contains(bare) {
                    Issue.record("Singular '\(word)' in the Arabic for: \(english)")
                }
            }
        }
    }

    @Test("Amharic asks politely on every screen, the form that suits anyone")
    func amharicAsksPolitely() {
        let familiar: Set<String> = ["ንካ", "ምረጥ"]
        let table = TranslationTable.shared.table(for: .amharic)
        #expect(!table.isEmpty)
        for (english, amharic) in table {
            let words = Set(amharic.split { !$0.isLetter }.map(String.init))
            for word in words.intersection(familiar) {
                Issue.record("Familiar '\(word)' in the Amharic for: \(english)")
            }
        }
    }

    @Test("French keeps : ; ? ! and » on the line of the word before them, so large text never strands one")
    func frenchPunctuationStaysWithItsWord() {
        let table = TranslationTable.shared.table(for: .french)
        #expect(!table.isEmpty)
        for (english, french) in table {
            for breakable in [" :", " ;", " ?", " !", " »", "« "] where french.contains(breakable) {
                Issue.record("Breakable '\(breakable)' in the French for: \(english)")
            }
        }
    }

    @Test("Russian and Ukrainian name a model as a model, and a voice as a voice, so a name like «Малая» or «Дана» keeps its own ending in any sentence")
    func modelAndVoiceNamesFollowTheirNoun() {
        let sentences = [
            "Delete model “%1”", "Delete %1 from the phone?", "Download %1 and switch to it?",
            "%1 (%2) is lighter and keeps up better here.", "Tap to download %1, %2, once over Wi\u{2011}Fi",
            "This model falls behind the speech on this phone. Tap to switch to %1, %2",
            "This model gets many Hebrew words wrong. Tap to switch to %1, %2",
            "This model was trained for Hebrew only. Tap to switch to %1, %2",
            "This phone may not have enough memory for %1. %2 (%3) is lighter.",
        ]
        for language in [UILanguage.russian, .ukrainian] {
            let table = TranslationTable.shared.table(for: language)
            for english in sentences {
                let text = table[english] ?? ""
                #expect(text.lowercased().contains("модел") && text.contains("«%1»"), "\(language): \(english) → \(text)")
            }
            for english in ["Delete %1", "Delete %1 from saved speakers?"] {
                let text = table[english] ?? ""
                #expect(text.contains("голос «%1»"), "\(language): \(english) → \(text)")
            }
        }
    }

    @Test("Hindi 'let it keep doing' takes the -ते form: Ozen को सुनते रहने दें, never सुनता रहने दें")
    func hindiKeepDoingTakesTheObliqueForm() {
        let table = TranslationTable.shared.table(for: .hindi)
        #expect(!table.isEmpty)
        for (english, hindi) in table where hindi.range(of: #"\S+(ता|ती) रहने द"#, options: .regularExpression) != nil {
            Issue.record("-ता/-ती before रहने देना in the Hindi for: \(english)")
        }
    }

    @Test("A setting or button named inside a sentence is quoted with the curly quotes the English uses, never straight ones")
    func namedSettingsTakeCurlyQuotes() {
        let straight = #"(^|[\s(])'[^'\s][^']{1,40}'([\s.,:;)!?]|$)"#
        for language in UILanguage.allCases {
            let table = TranslationTable.shared.table(for: language)
            for (english, translated) in table where translated.range(of: straight, options: .regularExpression) != nil {
                Issue.record("Straight quotes in the \(language) for: \(english)")
            }
        }
    }
}
