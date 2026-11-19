package com.arbelonson.ozen.core

import java.time.LocalDateTime
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockFormatTest {
    private val afternoon = LocalDateTime.of(2026, 10, 11, 15, 5, 7)

    private fun time(tag: String, use24: Boolean?, style: FormatStyle = FormatStyle.SHORT) =
        ClockFormat.formatter(null, style, Locale.forLanguageTag(tag), use24).format(afternoon)

    @Test
    fun `english with the 24-hour switch on reads 15 05`() {
        assertEquals("15:05", time("en-US", true))
    }

    @Test
    fun `hebrew with the 24-hour switch off reads in 12 hours`() {
        val shown = time("he-IL", false)
        assertTrue(shown.startsWith("3:05"), shown)
        assertTrue(shown.endsWith("PM") || shown.endsWith("אחה״צ") || shown.endsWith("אחר הצהריים"), shown)
    }

    @Test
    fun `without an override each language reads as it always did`() {
        for (tag in listOf("en-US", "he-IL", "de-DE", "ar", "zh-CN", "fr-FR")) {
            val locale = Locale.forLanguageTag(tag)
            val today = java.time.format.DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(afternoon)
            assertEquals(today, time(tag, null), tag)
        }
        assertEquals("3:05 PM", time("en-US", null))
        assertEquals("15:05", time("de-DE", null))
    }

    @Test
    fun `the switch carries into twelve and twenty-four hour forms of other languages`() {
        assertEquals("15:05", time("en-US", true))
        assertEquals("15:05", time("de-DE", true))
        assertEquals("15:05", time("he-IL", true))
        assertTrue(time("de-DE", false).startsWith("3:05"), time("de-DE", false))
        assertTrue(time("ja-JP", false).contains("3:05"), time("ja-JP", false))
        assertEquals("15:05:07", time("en-US", true, FormatStyle.MEDIUM))
        assertTrue(time("en-US", false, FormatStyle.MEDIUM).startsWith("3:05:07"))
    }

    @Test
    fun `a date with a time keeps its date when the hours change`() {
        val with24 = ClockFormat.formatter(FormatStyle.MEDIUM, FormatStyle.SHORT, Locale.US, true).format(afternoon)
        assertEquals("Oct 11, 2026, 15:05", with24)
    }

    @Test
    fun `the day-period mark stays beside the time in a Chinese date and time`() {
        val shown = ClockFormat.formatter(FormatStyle.MEDIUM, FormatStyle.SHORT, Locale.forLanguageTag("zh-CN"), false).format(afternoon)
        assertTrue(shown.startsWith("2026年10月11日"), shown)
        assertTrue(Regex("下午 ?3:05$").containsMatchIn(shown), shown)
    }
}
