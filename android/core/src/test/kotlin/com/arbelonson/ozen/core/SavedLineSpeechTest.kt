package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedLineSpeechTest {
    private fun line(text: String, speaker: String?, starred: Boolean = false) = SavedSegment(
        id = UUID.randomUUID(), text = text, speakerName = speaker, speakerClusterID = null,
        startTimestamp = 0.0, isCommitted = true, isStarred = starred,
    )

    @Test
    fun `the time shown above a line is read with it`() {
        assertEquals("21:04. דנה: בוקר טוב", SavedLineSpeech.label(line("בוקר טוב", "דנה"), time = "21:04", uncertain = false))
        assertEquals("דנה: בוקר טוב", SavedLineSpeech.label(line("בוקר טוב", "דנה"), time = null, uncertain = false))
    }

    @Test
    fun `an unrecognised voice is not read out as unknown speaker on every line, in any language it was saved in`() {
        for (language in UILanguage.entries) {
            val unknown = tr("דובר לא ידוע", "Unknown speaker", language)
            assertEquals("כן", SavedLineSpeech.label(line("כן", unknown), time = null, uncertain = false))
        }
        assertEquals("דובר 2: כן", SavedLineSpeech.label(line("כן", "דובר 2"), time = null, uncertain = false))
        assertEquals("כן", SavedLineSpeech.label(line("כן", ""), time = null, uncertain = false))
    }

    @Test
    fun `star and doubt come before the words, after the time`() {
        val label = SavedLineSpeech.label(line("כדור אחד", null, starred = true), time = "9:30", uncertain = true)
        assertEquals("9:30. מסומנת כחשובה. ייתכן שלא נשמעה נכון. כדור אחד", label)
    }
}

class SpokenDurationTest {
    private fun spoken(seconds: Double, language: UILanguage) = Localization.withLanguage(language) { ConversationStats.spokenDuration(seconds) }

    @Test
    fun `English says seconds, minutes and hours with the right singular and plural`() {
        val cases = listOf(
            0.0 to "0 seconds", 1.0 to "1 second", 5.0 to "5 seconds", 59.0 to "59 seconds",
            60.0 to "1 minute", 61.0 to "1 minute, 1 second", 62.0 to "1 minute, 2 seconds", 120.0 to "2 minutes",
            3_600.0 to "1 hour", 3_601.0 to "1 hour, 1 second", 3_661.0 to "1 hour, 1 minute, 1 second",
            3_727.0 to "1 hour, 2 minutes, 7 seconds", 7_200.0 to "2 hours", 7_260.0 to "2 hours, 1 minute",
        )
        for ((seconds, words) in cases) assertEquals(words, spoken(seconds, UILanguage.English), "$seconds")
    }

    @Test
    fun `a fraction of a second, a negative or a broken number reads like the clock does`() {
        assertEquals("5 seconds", spoken(5.9, UILanguage.English))
        assertEquals("0 seconds", spoken(-3.0, UILanguage.English))
        assertEquals("0 seconds", spoken(Double.NaN, UILanguage.English))
    }

    @Test
    fun `Hebrew uses its singular and dual forms`() {
        assertEquals("שנייה אחת", spoken(1.0, UILanguage.Hebrew))
        assertEquals("דקה אחת, שתי שניות", spoken(62.0, UILanguage.Hebrew))
        assertEquals("שעה אחת, שתי דקות, 7 שניות", spoken(3_727.0, UILanguage.Hebrew))
        assertEquals("שעתיים", spoken(7_200.0, UILanguage.Hebrew))
    }

    @Test
    fun `Russian and Arabic keep their own counting forms`() {
        assertEquals("1 час, 2 минуты, 7 секунд", spoken(3_727.0, UILanguage.Russian))
        assertEquals("دقيقة, ثانيتان", spoken(62.0, UILanguage.Arabic))
    }

    @Test
    fun `a recording's line is read with its time as words, not as a clock`() = Localization.withLanguage(UILanguage.English) {
        val segment = SavedSegment(UUID.randomUUID(), "hello", null, null, 1_062.0, true)
        val time = ConversationStats.spokenDuration(segment.startTimestamp - 1_000)
        assertEquals("1 minute, 2 seconds. hello", SavedLineSpeech.label(segment, time, uncertain = false))
    }
}
