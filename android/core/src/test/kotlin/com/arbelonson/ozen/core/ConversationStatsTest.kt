package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationStatsTest {
    private fun line(text: String, speaker: String?, at: Double, cluster: Int? = null) = SavedSegment(
        id = UUID.randomUUID(),
        text = text,
        speakerName = speaker,
        speakerClusterID = cluster,
        startTimestamp = at,
        isCommitted = true,
    )

    private fun <T> hebrew(block: () -> T): T = Localization.withLanguage(UILanguage.Hebrew, block)

    private fun <T> english(block: () -> T): T = Localization.withLanguage(UILanguage.English, block)

    private val record = TranscriptSessionRecord(
        startedAt = 0.0,
        endedAt = 720.0,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = null,
        inputName = null,
        segments = listOf(line("שלום לך", "רותי", 1.0), line("שלום", "אבי", 2.0)),
    )

    @Test
    fun `words are counted per speaker, Hebrew punctuation and niqqud don't make extra words`() {
        val stats = ConversationStats.compute(
            listOf(
                line("שָׁלוֹם, מה שלומך?", "רותי", 0.0),
                line("טוב, תודה!", "אבי", 5.0),
                line("ד״ר כהן התקשר", "רותי", 10.0),
            ),
        )
        assertEquals(8, stats.totalWords)
        assertEquals(listOf("רותי", "אבי"), stats.speakers.map { it.name })
        assertEquals(listOf(6, 2), stats.speakers.map { it.words })
    }

    @Test
    fun `consecutive lines by one speaker are one turn`() {
        val stats = ConversationStats.compute(
            listOf(
                line("אחת", "רותי", 0.0),
                line("שתיים", "רותי", 1.0),
                line("שלוש", "אבי", 2.0),
                line("ארבע", "רותי", 3.0),
                line("חמש", "רותי", 4.0),
            ),
        )
        assertEquals(3, stats.totalTurns)
        val ruti = stats.speakers.first { it.name == "רותי" }
        assertEquals(2, ruti.turns)
        assertEquals(4, ruti.words)
    }

    @Test
    fun `the longest turn sums the words of its consecutive lines`() {
        val stats = ConversationStats.compute(
            listOf(
                line("שלום לכולם", "רותי", 0.0),
                line("אני רוצה לספר לכם משהו", "אבי", 1.0),
                line("זה קרה אתמול", "אבי", 2.0),
                line("באמת?", "רותי", 3.0),
            ),
        )
        assertEquals(LongestTurn("אבי", 8), stats.longestTurn)
    }

    @Test
    fun `the turn still going when the conversation ends counts toward the longest`() {
        val stats = ConversationStats.compute(
            listOf(
                line("שלום", "רותי", 0.0),
                line("אני רוצה לספר לכם משהו", "אבי", 1.0),
                line("זה קרה אתמול", "אבי", 2.0),
            ),
        )
        assertEquals(LongestTurn("אבי", 8), stats.longestTurn)
        val alone = ConversationStats.compute(listOf(line("רק אני מדברת כאן", "רותי", 0.0)))
        assertEquals(LongestTurn("רותי", 4), alone.longestTurn)
    }

    @Test
    fun `fractions add up to one and a missing name becomes the unknown speaker`() = hebrew {
        val stats = ConversationStats.compute(
            listOf(
                line("אחת שתיים שלוש", null, 0.0),
                line("ארבע", "  ", 1.0),
                line("חמש שש שבע שמונה", "אבי", 2.0, cluster = 4),
            ),
        )
        assertEquals(listOf("אבי", ConversationStats.unknownSpeakerName), stats.speakers.map { it.name })
        val total = stats.speakers.sumOf { stats.wordFraction(it) }
        assertTrue(abs(total - 1) < 0.0001)
        assertEquals(4, stats.speakers.first().clusterID)
    }

    @Test
    fun `ties in word count are ordered by name so the list doesn't jump around`() {
        val stats = ConversationStats.compute(listOf(line("אחת", "רותי", 0.0), line("אחת", "אבי", 1.0)))
        assertEquals(listOf("אבי", "רותי"), stats.speakers.map { it.name })
    }

    @Test
    fun `duration uses the recorded session span when there is one, else first to last line`() {
        val segments = listOf(line("א ב", "רותי", 100.0), line("ג ד", "אבי", 160.0))
        val spanned = ConversationStats.compute(segments, startedAt = 90.0, endedAt = 210.0)
        assertEquals(120.0, spanned.durationSeconds)
        assertEquals(2.0, spanned.wordsPerMinute)
        val fallback = ConversationStats.compute(segments)
        assertEquals(60.0, fallback.durationSeconds)
        assertEquals(4.0, fallback.wordsPerMinute)
    }

    @Test
    fun `an empty conversation is all zeros, never a division by zero`() = hebrew {
        val stats = ConversationStats.compute(emptyList())
        assertEquals(0, stats.totalWords)
        assertEquals(0, stats.totalTurns)
        assertEquals(0.0, stats.durationSeconds)
        assertEquals(0.0, stats.wordsPerMinute)
        assertTrue(stats.speakers.isEmpty())
        assertNull(stats.longestTurn)
        assertEquals("פחות מדקה · אין מילים", stats.hebrewSummary)
    }

    @Test
    fun `the Hebrew summary uses the special forms for one and two`() = hebrew {
        assertEquals("פחות מדקה", ConversationStats.minutesText(20.0))
        assertEquals("דקה אחת", ConversationStats.minutesText(60.0))
        assertEquals("שתי דקות", ConversationStats.minutesText(125.0))
        assertEquals("12 דקות", ConversationStats.minutesText(12 * 60.0))
        assertEquals("59 דקות", ConversationStats.minutesText(59 * 60.0))
    }

    @Test
    fun `an hour or more is said the way people say it, an hour and a quarter, two and a half hours`() = hebrew {
        val minute = 60.0
        val expected = listOf(
            60 to "שעה", 61 to "שעה ודקה", 62 to "שעה ושתי דקות", 75 to "שעה ורבע", 90 to "שעה וחצי",
            95 to "שעה ו-35 דקות", 105 to "שעה ושלושה רבעים", 120 to "שעתיים", 180 to "3 שעות", 200 to "3 שעות ו-20 דקות",
        )
        for ((minutes, text) in expected) assertEquals(text, ConversationStats.minutesText(minutes * minute))
        assertEquals("שעתיים וחצי", ConversationStats.minutesText(150 * minute + 20))
        assertEquals(listOf("דובר אחד", "שני דוברים", "5 דוברים"), listOf(1, 2, 5).map(ConversationStats::speakersText))
        assertEquals(listOf("מילה אחת", "שתי מילים", "840 מילים"), listOf(1, 2, 840).map(ConversationStats::wordsText))
        assertEquals(listOf("שורה אחת", "שתי שורות", "14 שורות"), listOf(1, 2, 14).map { ConversationStats.linesText(it) })
        val starred = "מסומנת" to "מסומנות"
        assertEquals("שורה מסומנת אחת", ConversationStats.linesText(1, adjective = starred))
        assertEquals("3 שורות מסומנות", ConversationStats.linesText(3, adjective = starred))
        assertEquals("12 דקות · שני דוברים · 3 מילים", ConversationStats.compute(record).hebrewSummary)
    }

    private fun countPhrases(): List<String> {
        val counts = listOf(0, 1, 2, 3, 5, 11, 21, 100)
        return listOf(20.0, 60.0, 125.0, 3_600.0, 5_400.0).map(ConversationStats::minutesText) + counts.flatMap { count ->
            listOf(
                ConversationStats.speakersText(count),
                ConversationStats.linesText(count),
                ConversationStats.linesText(count, adjective = "חדשה" to "חדשות", englishAdjective = "new"),
                ConversationStats.wordsText(count),
                ConversationStats.secondsText(count),
                ConversationStats.aboutMinutesLeftText(count),
                ConversationStats.oldConversationsDeletedText(count),
                HebrewTime.minutesAgo(count),
            )
        }
    }

    @Test
    fun `in Hebrew, no count phrase borrows an English word`() = hebrew {
        for (phrase in countPhrases()) assertTrue(phrase.none { it.code < 128 && it.isLetter() }, phrase)
    }

    @Test
    fun `in every other language, no count phrase has a Hebrew word in it`() {
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            Localization.withLanguage(language) {
                for (phrase in countPhrases()) assertTrue(phrase.none { it in '֐'..'׿' }, "$language: $phrase")
            }
        }
    }

    @Test
    fun `English wording for minutes, speakers, words and lines`() = english {
        val minute = 60.0
        val expected = listOf(
            20.0 to "less than a minute", 60.0 to "1 minute", 125.0 to "2 minutes", 12 * minute to "12 minutes",
            60 * minute to "1 hour", 61 * minute to "1 hour 1 minute", 75 * minute to "1 hour 15 minutes",
            120 * minute to "2 hours", 200 * minute to "3 hours 20 minutes",
        )
        for ((seconds, text) in expected) assertEquals(text, ConversationStats.minutesText(seconds))
        assertEquals(listOf("1 speaker", "2 speakers"), listOf(1, 2).map(ConversationStats::speakersText))
        assertEquals(listOf("no words", "1 word", "2 words"), listOf(0, 1, 2).map(ConversationStats::wordsText))
        assertEquals("1 line", ConversationStats.linesText(1))
        assertEquals("3 lines", ConversationStats.linesText(3))
        assertEquals("1 starred line", ConversationStats.linesText(1, englishAdjective = "starred"))
        assertEquals("3 new lines", ConversationStats.linesText(3, englishAdjective = "new"))
        assertEquals("12 minutes · 2 speakers · 3 words", ConversationStats.compute(record).hebrewSummary)
    }

    @Test
    fun `the unknown speaker name follows the language`() = english {
        assertEquals("Unknown speaker", ConversationStats.unknownSpeakerName)
    }

    @Test
    fun `'Unknown speaker' saved in another language is the same unknown speaker, not an extra person`() {
        val stats = ConversationStats.compute(
            listOf(
                line("שלום לכולם", "רותי", 0.0),
                line("מה נשמע", "Unknown speaker", 5.0),
                line("הכל טוב", null, 9.0),
                line("יופי", "דובר לא ידוע", 12.0),
            ),
        )
        assertEquals(2, stats.speakers.size)
        assertTrue("רותי" in stats.speakers.map { it.name })
        assertEquals(5, stats.speakers.first { it.name != "רותי" }.words)
    }
}
