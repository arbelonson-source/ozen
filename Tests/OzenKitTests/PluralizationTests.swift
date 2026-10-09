import Testing
import Foundation
@testable import OzenKit

@Suite("Plural rules for the other ten languages")
struct PluralizationTests {
    private let hebrewLetters = ClosedRange<UInt32>(uncheckedBounds: (0x0590, 0x05FF))

    private func hasHebrewLetters(_ text: String) -> Bool {
        text.unicodeScalars.contains { hebrewLetters.contains($0.value) }
    }

    @Test("Russian one/few/many at the numbers where they actually differ")
    func russianEdgeNumbers() {
        Localization.$override.withValue(.russian) {
            #expect(ConversationStats.wordsText(1) == "1 слово")
            #expect(ConversationStats.wordsText(2) == "2 слова")
            #expect(ConversationStats.wordsText(5) == "5 слов")
            #expect(ConversationStats.wordsText(11) == "11 слов")
            #expect(ConversationStats.wordsText(21) == "21 слово")
            #expect(ConversationStats.wordsText(22) == "22 слова")
            #expect(ConversationStats.wordsText(25) == "25 слов")

            let starred = (singular: "מסומנת", plural: "מסומנות")
            #expect(ConversationStats.linesText(1, adjective: starred, englishAdjective: "new") == "1 новая строка")
            #expect(ConversationStats.linesText(3, adjective: starred, englishAdjective: "new") == "3 новые строки")
            #expect(ConversationStats.linesText(11, adjective: starred, englishAdjective: "new") == "11 новых строк")
        }
    }

    @Test("Arabic's zero/one/two/few/many/other, with its dual")
    func arabicEdgeNumbers() {
        Localization.$override.withValue(.arabic) {
            #expect(ConversationStats.linesText(0) == "0 سطر")
            #expect(ConversationStats.linesText(1) == "سطر")
            #expect(ConversationStats.linesText(2) == "سطران")
            #expect(ConversationStats.linesText(3) == "3 أسطر")
            #expect(ConversationStats.linesText(11) == "11 سطر")
            #expect(ConversationStats.linesText(100) == "100 سطر")

            #expect(ConversationStats.wordsText(0) == "لا كلمات")
            #expect(ConversationStats.wordsText(1) == "كلمة")
            #expect(ConversationStats.wordsText(2) == "كلمتان")
            #expect(ConversationStats.wordsText(3) == "3 كلمات")
            #expect(ConversationStats.wordsText(11) == "11 كلمة")
            #expect(ConversationStats.wordsText(100) == "100 كلمة")
        }
    }

    @Test("Speaking pace takes the word's form from the number")
    func speakingPace() {
        let expected: [(UILanguage, Double, String)] = [
            (.hebrew, 120, "120 מילים לדקה"),
            (.english, 120, "120 words per minute"),
            (.russian, 101, "101 слово в минуту"),
            (.russian, 102, "102 слова в минуту"),
            (.russian, 105, "105 слов в минуту"),
            (.ukrainian, 122, "122 слова за хвилину"),
            (.ukrainian, 125, "125 слів за хвилину"),
            (.arabic, 104, "104 كلمات في الدقيقة"),
            (.arabic, 120, "120 كلمة في الدقيقة"),
            (.german, 120, "120 Wörter pro Minute"),
            (.chineseSimplified, 120, "每分钟 120 字"),
        ]
        for (language, pace, text) in expected {
            Localization.$override.withValue(language) {
                #expect(ConversationStats.paceText(pace) == text, "\(language) \(pace)")
            }
        }
    }

    @Test("a voice sample's recording progress is read out with the right word form")
    func secondsSpoken() {
        let expected: [(UILanguage, Int, String)] = [
            (.hebrew, 1, "שנייה אחת"), (.hebrew, 2, "שתי שניות"), (.hebrew, 7, "7 שניות"),
            (.english, 1, "1 second"), (.english, 7, "7 seconds"),
            (.russian, 1, "1 секунда"), (.russian, 3, "3 секунды"), (.russian, 7, "7 секунд"),
            (.arabic, 1, "ثانية"), (.arabic, 5, "5 ثوانٍ"),
            (.german, 1, "1 Sekunde"), (.german, 7, "7 Sekunden"),
        ]
        for (language, count, text) in expected {
            Localization.$override.withValue(language) {
                #expect(ConversationStats.secondsText(count) == text, "\(language) \(count)")
            }
        }
    }

    @Test("a download's minutes left take the form 'about' needs: Russian and Ukrainian 21, 31, 41, 51; Arabic 3 to 10")
    func aboutMinutesLeft() {
        let expected: [(UILanguage, Int, String)] = [
            (.hebrew, 5, "עוד כ-5 דקות"),
            (.english, 21, "About 21 minutes left"),
            (.russian, 5, "Осталось около 5 минут"),
            (.russian, 21, "Осталось около 21 минуты"),
            (.russian, 11, "Осталось около 11 минут"),
            (.ukrainian, 31, "Залишилося близько 31 хвилини"),
            (.ukrainian, 12, "Залишилося близько 12 хвилин"),
            (.arabic, 5, "تبقّى نحو 5 دقائق"),
            (.arabic, 12, "تبقّى نحو 12 دقيقة"),
            (.german, 21, "Noch etwa 21 Minuten"),
        ]
        for (language, minutes, text) in expected {
            Localization.$override.withValue(language) {
                #expect(ConversationStats.aboutMinutesLeftText(minutes) == text, "\(language) \(minutes)")
            }
        }
    }

    @Test("'N old conversations will be deleted now' agrees with the number in Russian, Ukrainian and Arabic")
    func oldConversationsDeleted() {
        let expected: [(UILanguage, Int, String)] = [
            (.hebrew, 12, "12 שיחות ישנות יימחקו עכשיו"),
            (.english, 21, "21 old conversations will be deleted now"),
            (.russian, 3, "3 старых разговора будут удалены сейчас"),
            (.russian, 5, "5 старых разговоров будут удалены сейчас"),
            (.russian, 21, "21 старый разговор будет удалён сейчас"),
            (.russian, 13, "13 старых разговоров будут удалены сейчас"),
            (.ukrainian, 3, "3 старі розмови буде видалено зараз"),
            (.ukrainian, 21, "21 стару розмову буде видалено зараз"),
            (.ukrainian, 11, "11 старих розмов буде видалено зараз"),
            (.arabic, 5, "سيتم الآن حذف 5 محادثات قديمة"),
            (.arabic, 12, "سيتم الآن حذف 12 محادثة قديمة"),
        ]
        for (language, count, text) in expected {
            Localization.$override.withValue(language) {
                #expect(ConversationStats.oldConversationsDeletedText(count) == text, "\(language) \(count)")
            }
        }
    }

    @Test("French treats zero the same as one, unlike everything else")
    func frenchEdgeNumbers() {
        Localization.$override.withValue(.french) {
            #expect(ConversationStats.linesText(0) == "0 ligne")
            #expect(ConversationStats.linesText(1) == "1 ligne")
            #expect(ConversationStats.linesText(2) == "2 lignes")
            #expect(ConversationStats.linesText(3, englishAdjective: "new") == "3 nouvelles lignes")
            #expect(ConversationStats.linesText(1, englishAdjective: "new") == "1 nouvelle ligne")
        }
    }

    @Test("Chinese has no plural at all")
    func chineseEdgeNumbers() {
        Localization.$override.withValue(.chineseSimplified) {
            #expect(ConversationStats.wordsText(1) == "1 字")
            #expect(ConversationStats.wordsText(2) == "2 字")
            #expect(ConversationStats.linesText(1) == "1 行")
            #expect(ConversationStats.linesText(2) == "2 行")
        }
    }

    @Test("\"5 minutes ago\" in every one of the other ten languages")
    func fiveMinutesAgoEverywhere() {
        let expected: [UILanguage: String] = [
            .russian: "5 минут назад",
            .french: "il y a 5 minutes",
            .german: "vor 5 Minuten",
            .spanish: "hace 5 minutos",
            .portuguese: "há 5 minutos",
            .chineseSimplified: "5 分钟前",
            .hindi: "5 मिनट पहले",
            .arabic: "قبل 5 دقائق",
            .ukrainian: "5 хвилин тому",
            .amharic: "ከ5 ደቂቃ በፊት",
        ]
        for (language, text) in expected {
            Localization.$override.withValue(language) {
                #expect(HebrewTime.minutesAgo(5) == text, "\(language)")
            }
        }
    }

    @Test("Russian and Ukrainian \"a minute ago\" and \"an hour ago\" take the form that follows \"ago\"")
    func slavicAgoCase() {
        let expected: [(UILanguage, Int, String)] = [
            (.russian, 1, "1 минуту назад"),
            (.russian, 21, "21 минуту назад"),
            (.russian, 3, "3 минуты назад"),
            (.russian, 11, "11 минут назад"),
            (.russian, 60, "1 час назад"),
            (.ukrainian, 1, "1 хвилину тому"),
            (.ukrainian, 31, "31 хвилину тому"),
            (.ukrainian, 4, "4 хвилини тому"),
            (.ukrainian, 60, "1 годину тому"),
            (.ukrainian, 120, "2 години тому"),
            (.ukrainian, 300, "5 годин тому"),
        ]
        for (language, minutes, text) in expected {
            Localization.$override.withValue(language) {
                #expect(HebrewTime.minutesAgo(minutes) == text, "\(language) \(minutes)")
            }
        }
    }

    @Test("French, Spanish and Portuguese count one speaker in the singular and two in the plural")
    func speakersSingularAndPlural() {
        let expected: [(UILanguage, Int, String)] = [
            (.french, 1, "1 intervenant"),
            (.french, 2, "2 intervenants"),
            (.spanish, 1, "1 interlocutor"),
            (.spanish, 2, "2 interlocutores"),
            (.portuguese, 1, "1 interlocutor"),
            (.portuguese, 2, "2 interlocutores"),
        ]
        for (language, count, text) in expected {
            Localization.$override.withValue(language) {
                #expect(ConversationStats.speakersText(count) == text, "\(language) \(count)")
            }
        }
    }

    @Test("words, lines and speakers take the singular for one and the plural for two in the languages that split them so")
    func countedNounsSingularAndPlural() {
        let expected: [(UILanguage, (Int) -> String, Int, String)] = [
            (.french, ConversationStats.wordsText, 1, "1 mot"),
            (.french, ConversationStats.wordsText, 2, "2 mots"),
            (.spanish, ConversationStats.wordsText, 1, "1 palabra"),
            (.spanish, ConversationStats.wordsText, 2, "2 palabras"),
            (.german, ConversationStats.wordsText, 1, "1 Wort"),
            (.german, ConversationStats.wordsText, 2, "2 Wörter"),
            (.portuguese, ConversationStats.wordsText, 1, "1 palavra"),
            (.portuguese, ConversationStats.wordsText, 2, "2 palavras"),
            (.amharic, ConversationStats.wordsText, 1, "1 ቃል"),
            (.amharic, ConversationStats.wordsText, 2, "2 ቃላት"),
            (.french, { ConversationStats.linesText($0) }, 1, "1 ligne"),
            (.french, { ConversationStats.linesText($0) }, 2, "2 lignes"),
            (.spanish, { ConversationStats.linesText($0) }, 1, "1 línea"),
            (.spanish, { ConversationStats.linesText($0) }, 2, "2 líneas"),
            (.german, { ConversationStats.linesText($0) }, 1, "1 Zeile"),
            (.german, { ConversationStats.linesText($0) }, 2, "2 Zeilen"),
            (.portuguese, { ConversationStats.linesText($0) }, 1, "1 linha"),
            (.portuguese, { ConversationStats.linesText($0) }, 2, "2 linhas"),
            (.hindi, { ConversationStats.linesText($0) }, 1, "1 पंक्ति"),
            (.hindi, { ConversationStats.linesText($0) }, 2, "2 पंक्तियाँ"),
            (.amharic, { ConversationStats.linesText($0) }, 1, "1 መስመር"),
            (.amharic, { ConversationStats.linesText($0) }, 2, "2 መስመሮች"),
            (.amharic, ConversationStats.speakersText, 1, "1 ተናጋሪ"),
            (.amharic, ConversationStats.speakersText, 2, "2 ተናጋሪዎች"),
        ]
        for (language, text, count, wanted) in expected {
            Localization.$override.withValue(language) {
                #expect(text(count) == wanted, "\(language) \(count)")
            }
        }
    }

    @Test("hours and seconds take the singular for one and the plural for two in the languages that split them so")
    func timeUnitsSingularAndPlural() {
        let duration = { (minutes: Int) in ConversationStats.minutesText(Double(minutes * 60)) }
        let expected: [(UILanguage, (Int) -> String, Int, String)] = [
            (.french, duration, 60, "1 heure"),
            (.french, duration, 120, "2 heures"),
            (.spanish, duration, 60, "1 hora"),
            (.spanish, duration, 120, "2 horas"),
            (.german, duration, 60, "1 Stunde"),
            (.german, duration, 120, "2 Stunden"),
            (.portuguese, duration, 60, "1 hora"),
            (.portuguese, duration, 120, "2 horas"),
            (.hindi, duration, 60, "1 घंटा"),
            (.hindi, duration, 120, "2 घंटे"),
            (.french, HebrewTime.minutesAgo, 60, "il y a 1 heure"),
            (.french, HebrewTime.minutesAgo, 120, "il y a 2 heures"),
            (.spanish, HebrewTime.minutesAgo, 60, "hace 1 hora"),
            (.german, HebrewTime.minutesAgo, 120, "vor 2 Stunden"),
            (.portuguese, HebrewTime.minutesAgo, 60, "há 1 hora"),
            (.hindi, HebrewTime.minutesAgo, 60, "1 घंटा पहले"),
            (.hindi, HebrewTime.minutesAgo, 120, "2 घंटे पहले"),
            (.french, ConversationStats.secondsText, 1, "1 seconde"),
            (.french, ConversationStats.secondsText, 2, "2 secondes"),
            (.spanish, ConversationStats.secondsText, 1, "1 segundo"),
            (.spanish, ConversationStats.secondsText, 2, "2 segundos"),
            (.portuguese, ConversationStats.secondsText, 1, "1 segundo"),
            (.portuguese, ConversationStats.secondsText, 2, "2 segundos"),
        ]
        for (language, text, count, wanted) in expected {
            Localization.$override.withValue(language) {
                #expect(text(count) == wanted, "\(language) \(count)")
            }
        }
    }

    @Test("Arabic \"ago\" takes the dual after its preposition, and the numeral only from three on")
    func arabicAgoForms() {
        let expected: [(Int, String)] = [
            (1, "قبل دقيقة"),
            (2, "قبل دقيقتين"),
            (3, "قبل 3 دقائق"),
            (10, "قبل 10 دقائق"),
            (11, "قبل 11 دقيقة"),
            (60, "قبل ساعة"),
            (120, "قبل ساعتين"),
            (180, "قبل 3 ساعات"),
            (660, "قبل 11 ساعة"),
        ]
        Localization.$override.withValue(.arabic) {
            for (minutes, text) in expected {
                #expect(HebrewTime.minutesAgo(minutes) == text, "\(minutes)")
            }
        }
    }

    @Test("\"the conversation from 5 minutes ago was saved\" takes the ago phrase whole, with no second preposition")
    func savedConversationAgo() {
        let expected: [UILanguage: String] = [
            .hebrew: "השיחה מלפני 5 דקות נשמרה",
            .english: "The conversation from 5 minutes ago was saved",
            .french: "La conversation d’il y a 5 minutes a été enregistrée",
            .german: "Das Gespräch von vor 5 Minuten wurde gespeichert",
            .spanish: "Se guardó la conversación de hace 5 minutos",
            .amharic: "ውይይቱ ከ5 ደቂቃ በፊት ተቀምጧል",
        ]
        for (language, text) in expected {
            let ago = Localization.$override.withValue(language) { HebrewTime.minutesAgo(5) }
            #expect(tr("השיחה מ%1 נשמרה", "The conversation from %1 was saved", args: ["\(ago)"], in: language) == text, "\(language)")
        }
    }

    @Test("counts put into Russian, Ukrainian and Arabic sentences agree with the number")
    func countsInTranslatedSentences() {
        #expect(tr("%1 אחוז", "%1 percent", args: ["21"], in: .russian) == "21%")
        #expect(tr("%1 אחוז", "%1 percent", args: ["2"], in: .ukrainian) == "2%")
        #expect(tr("הסוללה ב-%1 אחוזים", "Battery at %1 percent", args: ["21"], in: .russian) == "Батарея на 21%")
        #expect(tr("הסוללה ב-%1 אחוזים", "Battery at %1 percent", args: ["2"], in: .ukrainian) == "Батарея на 2%")
        #expect(tr("הקלטה ושמירה (%1 שניות)", "Record and save (%1 seconds)", args: ["30"], in: .arabic) == "تسجيل وحفظ (30 ثانية)")
        let twoRecordings = RecordingImport.Result(added: ["Savta": 2])
        #expect(Localization.$override.withValue(.russian) { twoRecordings.summary }.contains("Savta (записей: 2)"))
        #expect(Localization.$override.withValue(.ukrainian) { twoRecordings.summary }.contains("Savta (записів: 2)"))
        #expect(Localization.$override.withValue(.arabic) { twoRecordings.summary }.contains("Savta (عدد التسجيلات: 2)"))
    }

    @Test("Amharic says the phone's space is free, as the table's own \"Free space\" does")
    func amharicFreeSpace() {
        let freeSpace = tr("מקום פנוי", "Free space", in: .amharic)
        #expect(tr("פנוי בטלפון: %1.", "Free on the phone: %1.", args: ["3 GB"], in: .amharic).contains(freeSpace))
    }

    @Test("Arabic joins hours and minutes with \"and\", which it needs when one and two carry no numeral")
    func arabicHoursAndMinutes() {
        Localization.$override.withValue(.arabic) {
            #expect(ConversationStats.minutesText(61 * 60) == "ساعة ودقيقة")
            #expect(ConversationStats.minutesText(122 * 60) == "ساعتان ودقيقتان")
            #expect(ConversationStats.minutesText(65 * 60) == "ساعة و5 دقائق")
            #expect(ConversationStats.minutesText(120 * 60) == "ساعتان")
        }
        Localization.$override.withValue(.russian) {
            #expect(ConversationStats.minutesText(65 * 60) == "1 час 5 минут")
        }
    }

    @Test("no function leaks a Hebrew word into another language's text")
    func noHebrewLeak() {
        for language in UILanguage.allCases where language != .hebrew {
            Localization.$override.withValue(language) {
                let counts = [0, 1, 2, 3, 5, 10, 11, 20, 21, 22, 25, 50, 60, 99, 100, 101]
                for count in counts {
                    #expect(!hasHebrewLetters(ConversationStats.wordsText(count)), "\(language) wordsText(\(count))")
                    #expect(!hasHebrewLetters(ConversationStats.speakersText(count)), "\(language) speakersText(\(count))")
                    #expect(!hasHebrewLetters(ConversationStats.linesText(count)), "\(language) linesText(\(count))")
                    let starred = (singular: "מסומנת", plural: "מסומנות")
                    #expect(!hasHebrewLetters(ConversationStats.linesText(count, adjective: starred, englishAdjective: "starred")), "\(language) linesText(\(count), starred)")
                    #expect(!hasHebrewLetters(ConversationStats.linesText(count, adjective: starred, englishAdjective: "new")), "\(language) linesText(\(count), new)")
                    #expect(!hasHebrewLetters(ConversationStats.linesText(count, englishAdjective: "unheardof")), "\(language) linesText(\(count), unheardof)")
                    #expect(!hasHebrewLetters(HebrewTime.minutesAgo(count)), "\(language) minutesAgo(\(count))")
                }
                let seconds: [Double] = [0, 20, 60, 90, 3600, 3660, 4500, 7200, 12000]
                for value in seconds {
                    #expect(!hasHebrewLetters(ConversationStats.minutesText(value)), "\(language) minutesText(\(value))")
                }
            }
        }
    }

    @Test("an adjective this file doesn't know falls back to English, never Hebrew")
    func unknownAdjectiveFallsBackToEnglish() {
        Localization.$override.withValue(.russian) {
            #expect(ConversationStats.linesText(3, englishAdjective: "unheardof") == "3 unheardof lines")
        }
    }
}
