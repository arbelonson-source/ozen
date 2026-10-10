package com.arbelonson.ozen.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

data class HistoryDay(
    val id: Long,
    val title: String,
    val sessions: List<TranscriptSessionSummary>,
)

object HistoryDays {
    fun grouped(
        sessions: List<TranscriptSessionSummary>,
        now: Double,
        utcOffsetSeconds: (Double) -> Int,
    ): List<HistoryDay> {
        val today = CivilDate.localDay(now, utcOffsetSeconds(now))
        val days = ArrayList<HistoryDay>()
        val indexOfDay = HashMap<Long, Int>()
        for (session in sessions) {
            val day = CivilDate.localDay(session.startedAt, utcOffsetSeconds(session.startedAt))
            val index = indexOfDay[day]
            if (index != null) {
                days[index] = days[index].copy(sessions = days[index].sessions + session)
            } else {
                indexOfDay[day] = days.size
                days.add(HistoryDay(day, title(day, today), listOf(session)))
            }
        }
        return days
    }

    fun title(of: Double, now: Double, utcOffsetSeconds: (Double) -> Int): String =
        title(
            day = CivilDate.localDay(of, utcOffsetSeconds(of)),
            today = CivilDate.localDay(now, utcOffsetSeconds(now)),
        )

    fun title(day: Long, today: Long): String {
        val language = Localization.language
        if (language == UILanguage.English) return englishTitle(day, today)
        if (language != UILanguage.Hebrew) return localizedTitle(day, today, language)
        val weekday = "יום ${weekdayNames[CivilDate.weekday(day)]}"
        return when (today - day) {
            0L -> "היום"
            1L -> "אתמול"
            in 2L..6L -> weekday
            else -> {
                val date = CivilDate.fromDaysSinceEpoch(day)
                val year = if (date.year == CivilDate.fromDaysSinceEpoch(today).year) "" else " ${date.year}"
                "$weekday, ${date.day} ב${monthNames[date.month - 1]}$year"
            }
        }
    }

    private fun englishTitle(day: Long, today: Long): String {
        val weekday = englishWeekdayNames[CivilDate.weekday(day)]
        return when (today - day) {
            0L -> "Today"
            1L -> "Yesterday"
            in 2L..6L -> weekday
            else -> {
                val date = CivilDate.fromDaysSinceEpoch(day)
                val year = if (date.year == CivilDate.fromDaysSinceEpoch(today).year) "" else " ${date.year}"
                "$weekday, ${date.day} ${englishMonthNames[date.month - 1]}$year"
            }
        }
    }

    /**
     * The other interface languages: each one's own names and date order
     * ("четверг, 5 марта", "jeudi 5 mars", "3月5日 星期四"), with a capital
     * letter as a heading has. The patterns are the ones the iPhone gets
     * from its date formatter for the same request (the weekday and the day
     * of the month, the month written out, and the year when it isn't this
     * one): Java's own pattern search shortens the month and the weekday.
     */
    private fun localizedTitle(day: Long, today: Long, language: UILanguage): String {
        val locale = language.formattingLocale
        return when (today - day) {
            0L -> tr("היום", "Today", language)
            1L -> tr("אתמול", "Yesterday", language)
            in 2L..6L -> {
                val weekday = CivilDate.weekday(day)
                heading(DayOfWeek.of(if (weekday == 0) 7 else weekday).getDisplayName(TextStyle.FULL, locale), language)
            }
            else -> {
                val sameYear = CivilDate.fromDaysSinceEpoch(day).year == CivilDate.fromDaysSinceEpoch(today).year
                val (thisYear, otherYear) = datePatterns.getValue(language)
                val formatter = DateTimeFormatter.ofPattern(if (sameYear) thisYear else otherYear, locale)
                heading(formatter.format(LocalDate.ofEpochDay(day)), language)
            }
        }
    }

    private fun heading(text: String, language: UILanguage): String =
        text.replaceFirstChar { it.titlecase(language.formattingLocale) }

    private val datePatterns = mapOf(
        UILanguage.Arabic to ("EEEE، d MMMM" to "EEEE، d MMMM، y"),
        UILanguage.Russian to ("cccc, d MMMM" to "EEEE, d MMMM y 'г'."),
        UILanguage.Amharic to ("EEEE፣ MMMM d" to "EEEE d MMMM y"),
        UILanguage.French to ("EEEE d MMMM" to "EEEE d MMMM y"),
        UILanguage.Spanish to ("EEEE, d 'de' MMMM" to "EEEE, d 'de' MMMM 'de' y"),
        UILanguage.Ukrainian to ("EEEE, d MMMM" to "EEEE, d MMMM y 'р'."),
        UILanguage.German to ("EEEE, d. MMMM" to "EEEE, d. MMMM y"),
        UILanguage.Portuguese to ("EEEE, d 'de' MMMM" to "EEEE, d 'de' MMMM 'de' y"),
        UILanguage.ChineseSimplified to ("M月d日 EEEE" to "y年M月d日 EEEE"),
        UILanguage.Hindi to ("EEEE, d MMMM" to "EEEE, d MMMM y"),
    )

    private val weekdayNames = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
    private val monthNames = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")
    private val englishWeekdayNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val englishMonthNames = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
}

/**
 * Past conversations that fell on today's month and day in an earlier
 * year: a year-old conversation with the same person resurfacing at the top
 * of History is worth the reminder, and costs no new data.
 */
object OnThisDay {
    // Each conversation's local day comes from its own offset from UTC: the
    // clock change moves by up to a week from year to year, so today's
    // offset could put a conversation near midnight on the wrong day and
    // make (or miss) an anniversary.
    fun matches(
        summaries: List<TranscriptSessionSummary>,
        now: Double,
        utcOffsetSeconds: (Double) -> Int,
    ): List<TranscriptSessionSummary> {
        val today = CivilDate.fromDaysSinceEpoch(CivilDate.localDay(now, utcOffsetSeconds(now)))
        return summaries
            .filter { summary ->
                val day = CivilDate.fromDaysSinceEpoch(CivilDate.localDay(summary.startedAt, utcOffsetSeconds(summary.startedAt)))
                day.month == today.month && day.day == today.day && day.year < today.year
            }
            .sortedByDescending { it.startedAt }
    }
}
