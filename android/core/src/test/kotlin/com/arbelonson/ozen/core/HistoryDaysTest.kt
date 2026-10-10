package com.arbelonson.ozen.core

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryDaysTest {
    private val israel = 3 * 3_600
    private val mondayNoonUTC = 1_789_387_200.0
    private val today = CivilDate.localDay(mondayNoonUTC, israel)

    private fun summary(startedAt: Double, preview: String = "שלום") = TranscriptSessionSummary(
        id = UUID.randomUUID(),
        startedAt = startedAt,
        endedAt = null,
        segmentCount = 1,
        preview = preview,
        engine = TranscriptionEngineKind.WhisperKit,
    )

    private fun titles(language: UILanguage, vararg back: Long) =
        Localization.withLanguage(language) { back.map { HistoryDays.title(today - it, today) } }

    @Test
    fun `today, yesterday, a weekday within the week, then the weekday with the date, and the year only when it isn't this one`() {
        assertEquals(CivilDate(2026, 9, 14), CivilDate.fromDaysSinceEpoch(today))
        assertEquals(
            listOf("היום", "אתמול", "יום שבת", "יום שלישי", "יום שני, 7 בספטמבר", "יום שלישי, 30 בדצמבר 2025", "יום שלישי, 15 בספטמבר"),
            titles(UILanguage.Hebrew, 0, 1, 2, 6, 7, 258, -1),
        )
        val lateSundayUTC = mondayNoonUTC - 13.5 * 3_600
        Localization.withLanguage(UILanguage.Hebrew) {
            assertEquals("היום", HistoryDays.title(of = lateSundayUTC, now = mondayNoonUTC) { israel })
            assertEquals("אתמול", HistoryDays.title(of = lateSundayUTC, now = mondayNoonUTC) { 0 })
        }
    }

    @Test
    fun `conversations keep their order under their local day, even when a day comes round again out of order`() {
        val hour = 3_600.0
        val late = summary(mondayNoonUTC - 2 * hour, preview = "late")
        val early = summary(mondayNoonUTC - 8 * hour, preview = "early")
        val lastNight = summary(mondayNoonUTC - 12 * hour, preview = "last night")
        val beforeMidnightUTC = summary(mondayNoonUTC - 13.5 * hour, preview = "after local midnight")
        val yesterday = summary(mondayNoonUTC - 20 * hour, preview = "yesterday")
        val days = Localization.withLanguage(UILanguage.Hebrew) {
            HistoryDays.grouped(listOf(late, early, lastNight, yesterday, beforeMidnightUTC), now = mondayNoonUTC) { israel }
        }
        assertEquals(listOf("היום", "אתמול"), days.map { it.title })
        assertEquals(listOf("late", "early", "last night", "after local midnight"), days.first().sessions.map { it.preview })
        assertEquals(listOf("yesterday"), days.last().sessions.map { it.preview })
        assertEquals(2, days.map { it.id }.toSet().size)
    }

    @Test
    fun `each conversation's day is taken at its own offset from UTC, so a clock change doesn't move it`() {
        val yesterdayLate = summary(mondayNoonUTC - 14 * 3_600 - 1_800)
        val days = Localization.withLanguage(UILanguage.Hebrew) {
            HistoryDays.grouped(listOf(yesterdayLate), now = mondayNoonUTC) { time ->
                if (time < mondayNoonUTC - 6 * 3_600) 2 * 3_600 else 3 * 3_600
            }
        }
        assertEquals(listOf("אתמול"), days.map { it.title })
    }

    @Test
    fun `nothing saved means no days`() {
        assertTrue(HistoryDays.grouped(emptyList(), now = mondayNoonUTC) { 0 }.isEmpty())
    }

    @Test
    fun `the other interface languages, their own today and yesterday, weekday and date order, never Hebrew`() {
        assertEquals("Сегодня", titles(UILanguage.Russian, 0).single())
        assertEquals(listOf("Суббота", "Понедельник, 7 сентября"), titles(UILanguage.Russian, 2, 7))
        assertEquals(listOf("Hier", "Mardi 30 décembre 2025"), titles(UILanguage.French, 1, 258))
        assertEquals("9月7日 星期一", titles(UILanguage.ChineseSimplified, 7).single())
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            for (title in titles(language, 0, 1, 2, 6, 7, 258)) {
                assertTrue(title.isNotEmpty() && title.none { it in '֐'..'׿' }, "$language: $title")
            }
        }
    }

    @Test
    fun `every other language's weekday and dates read as the iPhone writes them`() {
        val iPhone = mapOf(
            UILanguage.Arabic to listOf("السبت", "الاثنين، 7 سبتمبر", "الثلاثاء، 30 ديسمبر، 2025"),
            UILanguage.Russian to listOf("Суббота", "Понедельник, 7 сентября", "Вторник, 30 декабря 2025 г."),
            UILanguage.Amharic to listOf("ቅዳሜ", "ሰኞ፣ ሴፕቴምበር 7", "ማክሰኞ 30 ዲሴምበር 2025"),
            UILanguage.French to listOf("Samedi", "Lundi 7 septembre", "Mardi 30 décembre 2025"),
            UILanguage.Spanish to listOf("Sábado", "Lunes, 7 de septiembre", "Martes, 30 de diciembre de 2025"),
            UILanguage.Ukrainian to listOf("Субота", "Понеділок, 7 вересня", "Вівторок, 30 грудня 2025 р."),
            UILanguage.German to listOf("Samstag", "Montag, 7. September", "Dienstag, 30. Dezember 2025"),
            UILanguage.Portuguese to listOf("Sábado", "Segunda-feira, 7 de setembro", "Terça-feira, 30 de dezembro de 2025"),
            UILanguage.ChineseSimplified to listOf("星期六", "9月7日 星期一", "2025年12月30日 星期二"),
            UILanguage.Hindi to listOf("शनिवार", "सोमवार, 7 सितंबर", "मंगलवार, 30 दिसंबर 2025"),
        )
        for ((language, expected) in iPhone) assertEquals(expected, titles(language, 2, 7, 258), "$language")
    }

    @Test
    fun `a Sunday earlier in the week is named Sunday in every language`() {
        val sunday = today - 1
        val names = UILanguage.entries.associateWith { language ->
            Localization.withLanguage(language) { HistoryDays.title(sunday, sunday + 3) }
        }
        assertEquals(
            mapOf(
                UILanguage.Hebrew to "יום ראשון", UILanguage.English to "Sunday", UILanguage.Arabic to "الأحد",
                UILanguage.Russian to "Воскресенье", UILanguage.Amharic to "እሑድ", UILanguage.French to "Dimanche",
                UILanguage.Spanish to "Domingo", UILanguage.Ukrainian to "Неділя", UILanguage.German to "Sonntag",
                UILanguage.Portuguese to "Domingo", UILanguage.ChineseSimplified to "星期日", UILanguage.Hindi to "रविवार",
            ),
            names,
        )
    }

    @Test
    fun `English titles, today, yesterday, a weekday, then the weekday with the date`() {
        assertEquals(
            listOf("Today", "Yesterday", "Saturday", "Tuesday", "Monday, 7 September", "Tuesday, 30 December 2025"),
            titles(UILanguage.English, 0, 1, 2, 6, 7, 258),
        )
    }

    @Test
    fun `on this day, conversations on today's month and day in an earlier year, newest first, this year and other days are excluded`() {
        val oneYear = 365 * 86_400.0
        val yearAgo = mondayNoonUTC - oneYear
        val twoYearsAgo = yearAgo - oneYear
        val matches = OnThisDay.matches(
            listOf(
                summary(mondayNoonUTC, preview = "היום"),
                summary(mondayNoonUTC - 86_400, preview = "אתמול"),
                summary(twoYearsAgo, preview = "לפני שנתיים"),
                summary(yearAgo, preview = "לפני שנה"),
            ),
            now = mondayNoonUTC,
        ) { israel }
        assertEquals(listOf("לפני שנה", "לפני שנתיים"), matches.map { it.preview })
    }

    @Test
    fun `on this day, each session's own offset decides its local day, not today's, which would make a false match`() {
        fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
            LocalDateTime.of(year, month, day, hour, minute).toEpochSecond(ZoneOffset.UTC).toDouble()
        val session = timestamp(2025, 10, 26, 21, 40)
        val now = timestamp(2026, 10, 27, 9, 0)
        val transition = timestamp(2026, 1, 1, 0, 0)
        val matches = OnThisDay.matches(listOf(summary(session, preview = "אשתקד")), now = now) { time ->
            if (time < transition) 2 * 3_600 else 3 * 3_600
        }
        assertTrue(matches.isEmpty())
    }
}
