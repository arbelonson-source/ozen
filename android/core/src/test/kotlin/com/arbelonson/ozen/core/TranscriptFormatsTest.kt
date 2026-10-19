package com.arbelonson.ozen.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptFormatsTest {
    private val lines = listOf(
        TranscribedLine(0.0, 3.2, "Hello there."),
        TranscribedLine(65.5, 70.0, "שלום, מה שלומך?"),
    )

    @Test
    fun clockShowsMinutesAndSecondsAndAddsHoursPastAnHour() {
        assertEquals("0:00", TranscriptFormats.clock(0.0))
        assertEquals("0:09", TranscriptFormats.clock(9.99))
        assertEquals("1:05", TranscriptFormats.clock(65.5))
        assertEquals("59:59", TranscriptFormats.clock(3_599.4))
        assertEquals("1:00:00", TranscriptFormats.clock(3_600.0))
        assertEquals("2:03:07", TranscriptFormats.clock(7_387.0))
    }

    @Test
    fun plainTextHasOneLinePerCaptionWithItsStartTime() {
        assertEquals("0:00  Hello there.\n1:05  שלום, מה שלומך?", TranscriptFormats.text(lines))
    }

    @Test
    fun plainTextPastAnHourUsesHours() {
        assertEquals("1:02:03  Late line", TranscriptFormats.text(listOf(TranscribedLine(3_723.0, 3_725.0, "Late line"))))
    }

    @Test
    fun srtNumbersTheCuesAndUsesCommasInTimes() {
        val expected = "1\n00:00:00,000 --> 00:00:03,200\nHello there.\n\n2\n00:01:05,500 --> 00:01:10,000\nשלום, מה שלומך?\n"
        assertEquals(expected, TranscriptFormats.srt(lines))
    }

    @Test
    fun srtTimestampsPastAnHourKeepCountingHours() {
        val srt = TranscriptFormats.srt(listOf(TranscribedLine(3_725.25, 3_730.0, "One hour later")))
        assertEquals("1\n01:02:05,250 --> 01:02:10,000\nOne hour later\n", srt)
    }

    @Test
    fun srtTimesAreCutOffNeverRoundedUpIntoTheNextSecond() {
        val srt = TranscriptFormats.srt(listOf(TranscribedLine(59.9996, 61.0, "x")))
        assertEquals("1\n00:00:59,999 --> 00:01:01,000\nx\n", srt)
    }

    @Test
    fun srtKeepsABlankLineOutOfACue() {
        val srt = TranscriptFormats.srt(listOf(TranscribedLine(1.0, 2.0, "first\n\nsecond")))
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nfirst second\n", srt)
    }

    @Test
    fun srtGivesEveryCueSomeTimeOnScreen() {
        val srt = TranscriptFormats.srt(listOf(TranscribedLine(5.0, 5.0, "short")))
        assertEquals("1\n00:00:05,000 --> 00:00:05,500\nshort\n", srt)
    }

    @Test
    fun vttStartsWithItsHeaderAndUsesDotsInTimes() {
        val expected = "WEBVTT\n\n00:00:00.000 --> 00:00:03.200\nHello there.\n\n00:01:05.500 --> 00:01:10.000\nשלום, מה שלומך?\n"
        assertEquals(expected, TranscriptFormats.vtt(lines))
    }

    @Test
    fun anEmptyTranscriptGivesEmptyTextAndAnEmptySubtitleFile() {
        assertEquals("", TranscriptFormats.text(emptyList()))
        assertEquals("", TranscriptFormats.srt(emptyList()))
        assertEquals("WEBVTT\n", TranscriptFormats.vtt(emptyList()))
    }

    @Test
    fun subtitleTimestampsStayAsciiOnAPhoneWhoseNumbersUseOtherDigits() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val one = listOf(TranscribedLine(3_723.456, 3_725.0, "Late line"))
            assertEquals("1\n01:02:03,456 --> 01:02:05,000\nLate line\n", TranscriptFormats.srt(one))
            assertEquals("WEBVTT\n\n01:02:03.456 --> 01:02:05.000\nLate line\n", TranscriptFormats.vtt(one))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
