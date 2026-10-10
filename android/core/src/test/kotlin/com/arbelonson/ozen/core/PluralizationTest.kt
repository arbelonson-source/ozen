package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluralizationTest {
    @Test
    fun `English counts one second and one hour in the singular, more in the plural`() {
        assertTrue(
            TimeUnitWord.second(PluralCategory.One, UILanguage.English) == "second" &&
                TimeUnitWord.second(PluralCategory.Other, UILanguage.English) == "seconds",
        )
        assertTrue(
            TimeUnitWord.hour(PluralCategory.One, UILanguage.English) == "hour" &&
                TimeUnitWord.hour(PluralCategory.Other, UILanguage.English) == "hours",
        )
        assertTrue(
            TimeUnitWord.minute(PluralCategory.One, UILanguage.English) == "minute" &&
                TimeUnitWord.minute(PluralCategory.Other, UILanguage.English) == "minutes",
        )
    }

    @Test
    fun `plural categories fall where each language's rule puts them`() {
        fun categories(language: UILanguage, counts: List<Int>) = counts.map { pluralCategory(it, language) }
        val counts = listOf(0, 1, 2, 3, 5, 11, 12, 21, 22, 25, 100, 101, 111)
        assertEquals(
            listOf("Many", "One", "Few", "Few", "Many", "Many", "Many", "One", "Few", "Many", "Many", "One", "Many"),
            categories(UILanguage.Russian, counts).map { it.name },
        )
        assertEquals(
            listOf("Zero", "One", "Two", "Few", "Few", "Many", "Many", "Many", "Many", "Many", "Other", "Other", "Many"),
            categories(UILanguage.Arabic, counts).map { it.name },
        )
        assertEquals(PluralCategory.One, pluralCategory(0, UILanguage.French))
        assertEquals(PluralCategory.Other, pluralCategory(0, UILanguage.German))
        assertEquals(PluralCategory.Other, pluralCategory(1, UILanguage.ChineseSimplified))
        assertEquals(PluralCategory.Other, pluralCategory(2, UILanguage.English))
    }

    private fun hasHebrewLetters(text: String) = text.any { it in '֐'..'׿' }

    private fun expectAll(cases: List<Triple<UILanguage, Int, String>>, text: (Int) -> String) {
        for ((language, count, wanted) in cases) {
            assertEquals(wanted, Localization.withLanguage(language) { text(count) }, "$language $count")
        }
    }

    @Test
    fun `Russian one, few and many at the numbers where they actually differ`() = Localization.withLanguage(UILanguage.Russian) {
        assertEquals(
            listOf("1 слово", "2 слова", "5 слов", "11 слов", "21 слово", "22 слова", "25 слов"),
            listOf(1, 2, 5, 11, 21, 22, 25).map(ConversationStats::wordsText),
        )
        val starred = "מסומנת" to "מסומנות"
        assertEquals("1 новая строка", ConversationStats.linesText(1, starred, "new"))
        assertEquals("3 новые строки", ConversationStats.linesText(3, starred, "new"))
        assertEquals("11 новых строк", ConversationStats.linesText(11, starred, "new"))
    }

    @Test
    fun `Arabic's zero, one, two, few, many and other, with its dual`() = Localization.withLanguage(UILanguage.Arabic) {
        assertEquals(
            listOf("0 سطر", "سطر", "سطران", "3 أسطر", "11 سطر", "100 سطر"),
            listOf(0, 1, 2, 3, 11, 100).map { ConversationStats.linesText(it) },
        )
        assertEquals(
            listOf("لا كلمات", "كلمة", "كلمتان", "3 كلمات", "11 كلمة", "100 كلمة"),
            listOf(0, 1, 2, 3, 11, 100).map(ConversationStats::wordsText),
        )
    }

    @Test
    fun `speaking pace takes the word's form from the number`() {
        val expected = listOf(
            Triple(UILanguage.Hebrew, 120, "120 מילים לדקה"),
            Triple(UILanguage.English, 120, "120 words per minute"),
            Triple(UILanguage.Russian, 101, "101 слово в минуту"),
            Triple(UILanguage.Russian, 102, "102 слова в минуту"),
            Triple(UILanguage.Russian, 105, "105 слов в минуту"),
            Triple(UILanguage.Russian, 111, "111 слов в минуту"),
            Triple(UILanguage.Russian, 112, "112 слов в минуту"),
            Triple(UILanguage.Ukrainian, 122, "122 слова за хвилину"),
            Triple(UILanguage.Ukrainian, 125, "125 слів за хвилину"),
            Triple(UILanguage.Arabic, 103, "103 كلمات في الدقيقة"),
            Triple(UILanguage.Arabic, 104, "104 كلمات في الدقيقة"),
            Triple(UILanguage.Arabic, 111, "111 كلمة في الدقيقة"),
            Triple(UILanguage.Arabic, 120, "120 كلمة في الدقيقة"),
            Triple(UILanguage.German, 120, "120 Wörter pro Minute"),
            Triple(UILanguage.ChineseSimplified, 120, "每分钟 120 字"),
        )
        expectAll(expected) { ConversationStats.paceText(it.toDouble()) }
    }

    @Test
    fun `a voice sample's recording progress is read out with the right word form`() {
        val expected = listOf(
            Triple(UILanguage.Hebrew, 1, "שנייה אחת"), Triple(UILanguage.Hebrew, 2, "שתי שניות"), Triple(UILanguage.Hebrew, 7, "7 שניות"),
            Triple(UILanguage.English, 1, "1 second"), Triple(UILanguage.English, 7, "7 seconds"),
            Triple(UILanguage.Russian, 1, "1 секунда"), Triple(UILanguage.Russian, 3, "3 секунды"), Triple(UILanguage.Russian, 7, "7 секунд"),
            Triple(UILanguage.Arabic, 1, "ثانية"), Triple(UILanguage.Arabic, 5, "5 ثوانٍ"),
            Triple(UILanguage.German, 1, "1 Sekunde"), Triple(UILanguage.German, 7, "7 Sekunden"),
        )
        expectAll(expected, ConversationStats::secondsText)
    }

    @Test
    fun `a download's minutes left take the form 'about' needs, Russian and Ukrainian 21, 31, 41, 51, Arabic 3 to 10`() {
        val expected = listOf(
            Triple(UILanguage.Hebrew, 5, "עוד כ-5 דקות"),
            Triple(UILanguage.English, 21, "About 21 minutes left"),
            Triple(UILanguage.Russian, 5, "Осталось около 5 минут"),
            Triple(UILanguage.Russian, 21, "Осталось около 21 минуты"),
            Triple(UILanguage.Russian, 11, "Осталось около 11 минут"),
            Triple(UILanguage.Ukrainian, 31, "Залишилося близько 31 хвилини"),
            Triple(UILanguage.Ukrainian, 12, "Залишилося близько 12 хвилин"),
            Triple(UILanguage.Arabic, 3, "تبقّى نحو 3 دقائق"),
            Triple(UILanguage.Arabic, 5, "تبقّى نحو 5 دقائق"),
            Triple(UILanguage.Arabic, 10, "تبقّى نحو 10 دقائق"),
            Triple(UILanguage.Arabic, 11, "تبقّى نحو 11 دقيقة"),
            Triple(UILanguage.Arabic, 12, "تبقّى نحو 12 دقيقة"),
            Triple(UILanguage.German, 21, "Noch etwa 21 Minuten"),
        )
        expectAll(expected, ConversationStats::aboutMinutesLeftText)
    }

    @Test
    fun `'N old conversations will be deleted now' agrees with the number in Russian, Ukrainian and Arabic`() {
        val expected = listOf(
            Triple(UILanguage.Hebrew, 12, "12 שיחות ישנות יימחקו עכשיו"),
            Triple(UILanguage.English, 21, "21 old conversations will be deleted now"),
            Triple(UILanguage.Russian, 3, "3 старых разговора будут удалены сейчас"),
            Triple(UILanguage.Russian, 5, "5 старых разговоров будут удалены сейчас"),
            Triple(UILanguage.Russian, 21, "21 старый разговор будет удалён сейчас"),
            Triple(UILanguage.Russian, 13, "13 старых разговоров будут удалены сейчас"),
            Triple(UILanguage.Ukrainian, 3, "3 старі розмови буде видалено зараз"),
            Triple(UILanguage.Ukrainian, 21, "21 стару розмову буде видалено зараз"),
            Triple(UILanguage.Ukrainian, 11, "11 старих розмов буде видалено зараз"),
            Triple(UILanguage.Arabic, 5, "سيتم الآن حذف 5 محادثات قديمة"),
            Triple(UILanguage.Arabic, 12, "سيتم الآن حذف 12 محادثة قديمة"),
        )
        expectAll(expected, ConversationStats::oldConversationsDeletedText)
    }

    @Test
    fun `French treats zero the same as one, unlike everything else`() = Localization.withLanguage(UILanguage.French) {
        assertEquals(listOf("0 ligne", "1 ligne", "2 lignes"), listOf(0, 1, 2).map { ConversationStats.linesText(it) })
        assertEquals("3 nouvelles lignes", ConversationStats.linesText(3, englishAdjective = "new"))
        assertEquals("1 nouvelle ligne", ConversationStats.linesText(1, englishAdjective = "new"))
    }

    @Test
    fun `Chinese has no plural at all`() = Localization.withLanguage(UILanguage.ChineseSimplified) {
        assertEquals(listOf("1 字", "2 字"), listOf(1, 2).map(ConversationStats::wordsText))
        assertEquals(listOf("1 行", "2 行"), listOf(1, 2).map { ConversationStats.linesText(it) })
    }

    @Test
    fun `5 minutes ago in every one of the other ten languages`() {
        val expected = mapOf(
            UILanguage.Russian to "5 минут назад",
            UILanguage.French to "il y a 5 minutes",
            UILanguage.German to "vor 5 Minuten",
            UILanguage.Spanish to "hace 5 minutos",
            UILanguage.Portuguese to "há 5 minutos",
            UILanguage.ChineseSimplified to "5 分钟前",
            UILanguage.Hindi to "5 मिनट पहले",
            UILanguage.Arabic to "قبل 5 دقائق",
            UILanguage.Ukrainian to "5 хвилин тому",
            UILanguage.Amharic to "ከ5 ደቂቃ በፊት",
        )
        expectAll(expected.map { (language, text) -> Triple(language, 5, text) }, HebrewTime::minutesAgo)
    }

    @Test
    fun `Russian and Ukrainian a minute ago and an hour ago take the form that follows ago`() {
        val expected = listOf(
            Triple(UILanguage.Russian, 1, "1 минуту назад"),
            Triple(UILanguage.Russian, 21, "21 минуту назад"),
            Triple(UILanguage.Russian, 3, "3 минуты назад"),
            Triple(UILanguage.Russian, 11, "11 минут назад"),
            Triple(UILanguage.Russian, 60, "1 час назад"),
            Triple(UILanguage.Ukrainian, 1, "1 хвилину тому"),
            Triple(UILanguage.Ukrainian, 31, "31 хвилину тому"),
            Triple(UILanguage.Ukrainian, 4, "4 хвилини тому"),
            Triple(UILanguage.Ukrainian, 60, "1 годину тому"),
            Triple(UILanguage.Ukrainian, 120, "2 години тому"),
            Triple(UILanguage.Ukrainian, 300, "5 годин тому"),
        )
        expectAll(expected, HebrewTime::minutesAgo)
    }

    @Test
    fun `French, Spanish and Portuguese count one speaker in the singular and two in the plural`() {
        val expected = listOf(
            Triple(UILanguage.French, 1, "1 intervenant"),
            Triple(UILanguage.French, 2, "2 intervenants"),
            Triple(UILanguage.Spanish, 1, "1 interlocutor"),
            Triple(UILanguage.Spanish, 2, "2 interlocutores"),
            Triple(UILanguage.Portuguese, 1, "1 interlocutor"),
            Triple(UILanguage.Portuguese, 2, "2 interlocutores"),
        )
        expectAll(expected, ConversationStats::speakersText)
    }

    @Test
    fun `words, lines and speakers take the singular for one and the plural for two in the languages that split them so`() {
        val words = listOf(
            Triple(UILanguage.French, 1, "1 mot"), Triple(UILanguage.French, 2, "2 mots"),
            Triple(UILanguage.Spanish, 1, "1 palabra"), Triple(UILanguage.Spanish, 2, "2 palabras"),
            Triple(UILanguage.German, 1, "1 Wort"), Triple(UILanguage.German, 2, "2 Wörter"),
            Triple(UILanguage.Portuguese, 1, "1 palavra"), Triple(UILanguage.Portuguese, 2, "2 palavras"),
            Triple(UILanguage.Amharic, 1, "1 ቃል"), Triple(UILanguage.Amharic, 2, "2 ቃላት"),
        )
        val lines = listOf(
            Triple(UILanguage.French, 1, "1 ligne"), Triple(UILanguage.French, 2, "2 lignes"),
            Triple(UILanguage.Spanish, 1, "1 línea"), Triple(UILanguage.Spanish, 2, "2 líneas"),
            Triple(UILanguage.German, 1, "1 Zeile"), Triple(UILanguage.German, 2, "2 Zeilen"),
            Triple(UILanguage.Portuguese, 1, "1 linha"), Triple(UILanguage.Portuguese, 2, "2 linhas"),
            Triple(UILanguage.Hindi, 1, "1 पंक्ति"), Triple(UILanguage.Hindi, 2, "2 पंक्तियाँ"),
            Triple(UILanguage.Amharic, 1, "1 መስመር"), Triple(UILanguage.Amharic, 2, "2 መስመሮች"),
        )
        expectAll(words, ConversationStats::wordsText)
        expectAll(lines) { ConversationStats.linesText(it) }
        expectAll(listOf(Triple(UILanguage.Amharic, 1, "1 ተናጋሪ"), Triple(UILanguage.Amharic, 2, "2 ተናጋሪዎች")), ConversationStats::speakersText)
    }

    @Test
    fun `hours and seconds take the singular for one and the plural for two in the languages that split them so`() {
        val durations = listOf(
            Triple(UILanguage.French, 60, "1 heure"), Triple(UILanguage.French, 120, "2 heures"),
            Triple(UILanguage.Spanish, 60, "1 hora"), Triple(UILanguage.Spanish, 120, "2 horas"),
            Triple(UILanguage.German, 60, "1 Stunde"), Triple(UILanguage.German, 120, "2 Stunden"),
            Triple(UILanguage.Portuguese, 60, "1 hora"), Triple(UILanguage.Portuguese, 120, "2 horas"),
            Triple(UILanguage.Hindi, 60, "1 घंटा"), Triple(UILanguage.Hindi, 120, "2 घंटे"),
        )
        val ago = listOf(
            Triple(UILanguage.French, 60, "il y a 1 heure"), Triple(UILanguage.French, 120, "il y a 2 heures"),
            Triple(UILanguage.Spanish, 60, "hace 1 hora"), Triple(UILanguage.German, 120, "vor 2 Stunden"),
            Triple(UILanguage.Portuguese, 60, "há 1 hora"),
            Triple(UILanguage.Hindi, 60, "1 घंटा पहले"), Triple(UILanguage.Hindi, 120, "2 घंटे पहले"),
        )
        val seconds = listOf(
            Triple(UILanguage.French, 1, "1 seconde"), Triple(UILanguage.French, 2, "2 secondes"),
            Triple(UILanguage.Spanish, 1, "1 segundo"), Triple(UILanguage.Spanish, 2, "2 segundos"),
            Triple(UILanguage.Portuguese, 1, "1 segundo"), Triple(UILanguage.Portuguese, 2, "2 segundos"),
        )
        expectAll(durations) { ConversationStats.minutesText(it * 60.0) }
        expectAll(ago, HebrewTime::minutesAgo)
        expectAll(seconds, ConversationStats::secondsText)
    }

    @Test
    fun `under half a minute is less than a minute in each of the other ten languages, and a minute is counted`() {
        val expected = mapOf(
            UILanguage.Russian to ("меньше минуты" to "1 минута"),
            UILanguage.Ukrainian to ("менше хвилини" to "1 хвилина"),
            UILanguage.Arabic to ("أقل من دقيقة" to "دقيقة"),
            UILanguage.French to ("moins d'une minute" to "1 minute"),
            UILanguage.Spanish to ("menos de un minuto" to "1 minuto"),
            UILanguage.German to ("weniger als eine Minute" to "1 Minute"),
            UILanguage.Portuguese to ("menos de um minuto" to "1 minuto"),
            UILanguage.Hindi to ("एक मिनट से कम" to "1 मिनट"),
            UILanguage.Amharic to ("ከአንድ ደቂቃ በታች" to "1 ደቂቃ"),
            UILanguage.ChineseSimplified to ("不到一分钟" to "1 分钟"),
        )
        for ((language, text) in expected) {
            Localization.withLanguage(language) {
                assertEquals(text.first, ConversationStats.minutesText(20.0), "$language")
                assertEquals(text.second, ConversationStats.minutesText(60.0), "$language")
            }
        }
    }

    @Test
    fun `Arabic ago takes the dual after its preposition, and the numeral only from three on`() {
        val expected = listOf(
            1 to "قبل دقيقة", 2 to "قبل دقيقتين", 3 to "قبل 3 دقائق", 10 to "قبل 10 دقائق", 11 to "قبل 11 دقيقة",
            60 to "قبل ساعة", 120 to "قبل ساعتين", 180 to "قبل 3 ساعات", 660 to "قبل 11 ساعة",
        )
        expectAll(expected.map { (minutes, text) -> Triple(UILanguage.Arabic, minutes, text) }, HebrewTime::minutesAgo)
    }

    @Test
    fun `the conversation from 5 minutes ago was saved takes the ago phrase whole, with no second preposition`() {
        val expected = mapOf(
            UILanguage.Hebrew to "השיחה מלפני 5 דקות נשמרה",
            UILanguage.English to "The conversation from 5 minutes ago was saved",
            UILanguage.French to "La conversation d’il y a 5 minutes a été enregistrée",
            UILanguage.German to "Das Gespräch von vor 5 Minuten wurde gespeichert",
            UILanguage.Spanish to "Se guardó la conversación de hace 5 minutos",
            UILanguage.Amharic to "ውይይቱ ከ5 ደቂቃ በፊት ተቀምጧል",
        )
        for ((language, text) in expected) {
            val ago = Localization.withLanguage(language) { HebrewTime.minutesAgo(5) }
            assertEquals(text, tr("השיחה מ%1 נשמרה", "The conversation from %1 was saved", listOf(ago), language), "$language")
        }
    }

    @Test
    fun `counts put into Russian, Ukrainian and Arabic sentences agree with the number`() {
        assertEquals("21%", tr("%1 אחוז", "%1 percent", listOf("21"), UILanguage.Russian))
        assertEquals("2%", tr("%1 אחוז", "%1 percent", listOf("2"), UILanguage.Ukrainian))
        assertEquals("Батарея на 21%", tr("הסוללה ב-%1 אחוזים", "Battery at %1 percent", listOf("21"), UILanguage.Russian))
        assertEquals("Батарея на 2%", tr("הסוללה ב-%1 אחוזים", "Battery at %1 percent", listOf("2"), UILanguage.Ukrainian))
        assertEquals("تسجيل وحفظ (30 ثانية)", tr("הקלטה ושמירה (%1 שניות)", "Record and save (%1 seconds)", listOf("30"), UILanguage.Arabic))
        val twoRecordings = RecordingImport.Result(added = mapOf("Savta" to 2))
        assertTrue("Savta (записей: 2)" in Localization.withLanguage(UILanguage.Russian) { twoRecordings.summary })
        assertTrue("Savta (записів: 2)" in Localization.withLanguage(UILanguage.Ukrainian) { twoRecordings.summary })
        assertTrue("Savta (عدد التسجيلات: 2)" in Localization.withLanguage(UILanguage.Arabic) { twoRecordings.summary })
    }

    @Test
    fun `Amharic says the phone's space is free, as the table's own Free space does`() {
        val freeSpace = tr("מקום פנוי", "Free space", UILanguage.Amharic)
        assertTrue(freeSpace in tr("פנוי בטלפון: %1.", "Free on the phone: %1.", listOf("3 GB"), UILanguage.Amharic))
    }

    @Test
    fun `Arabic joins hours and minutes with and, which it needs when one and two carry no numeral`() {
        Localization.withLanguage(UILanguage.Arabic) {
            assertEquals("ساعة ودقيقة", ConversationStats.minutesText(61 * 60.0))
            assertEquals("ساعتان ودقيقتان", ConversationStats.minutesText(122 * 60.0))
            assertEquals("ساعة و5 دقائق", ConversationStats.minutesText(65 * 60.0))
            assertEquals("ساعتان", ConversationStats.minutesText(120 * 60.0))
        }
        assertEquals("1 час 5 минут", Localization.withLanguage(UILanguage.Russian) { ConversationStats.minutesText(65 * 60.0) })
    }

    @Test
    fun `no function leaks a Hebrew word into another language's text`() {
        val starred = "מסומנת" to "מסומנות"
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            Localization.withLanguage(language) {
                for (count in listOf(0, 1, 2, 3, 5, 10, 11, 20, 21, 22, 25, 50, 60, 99, 100, 101)) {
                    val texts = listOf(
                        ConversationStats.wordsText(count),
                        ConversationStats.speakersText(count),
                        ConversationStats.linesText(count),
                        ConversationStats.linesText(count, starred, "starred"),
                        ConversationStats.linesText(count, starred, "new"),
                        ConversationStats.linesText(count, englishAdjective = "unheardof"),
                        HebrewTime.minutesAgo(count),
                    )
                    for (text in texts) assertTrue(!hasHebrewLetters(text), "$language $count: $text")
                }
                for (seconds in listOf(0.0, 20.0, 60.0, 90.0, 3600.0, 3660.0, 4500.0, 7200.0, 12000.0)) {
                    val text = ConversationStats.minutesText(seconds)
                    assertTrue(!hasHebrewLetters(text), "$language $seconds: $text")
                }
            }
        }
    }

    @Test
    fun `an adjective this file doesn't know falls back to English, never Hebrew`() {
        assertEquals("3 unheardof lines", Localization.withLanguage(UILanguage.Russian) { ConversationStats.linesText(3, englishAdjective = "unheardof") })
    }
}
