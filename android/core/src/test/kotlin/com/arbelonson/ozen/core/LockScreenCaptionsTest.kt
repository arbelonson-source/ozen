package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PipelineCaptionsChangedTest {
    @Test
    fun `each new or changed line, and clearing, calls the hook`() = runTest {
        val engine = FakeEngine()
        val pipeline = captionPipeline(audio = FakeAudioCapturer(), engineFactory = { engine }, embedder = FakeEmbedder(), now = { 1_001.0 })
        var calls = 0
        pipeline.onCaptionsChanged = { calls += 1 }
        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()
        engine.emit(TranscriptToken(utteranceID = id, text = "hello", isFinal = false, timestamp = 1_000.0))
        assertTrue(eventually { calls == 1 })
        engine.emit(TranscriptToken(utteranceID = id, text = "hello there", isFinal = true, timestamp = 1_001.0))
        assertTrue(eventually { calls == 2 })
        pipeline.clearTranscript()
        assertEquals(3, calls)
    }
}

class LockScreenCaptionsTest {
    private fun line(text: String, speaker: Int? = null, final: Boolean = true) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = final, speakerClusterID = speaker, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)

    private val names: (TranscriptSegment) -> String? = { segment -> segment.speakerClusterID?.let { "Speaker $it" } }

    // The marks are invisible and cost none of the line's drawn width.
    private fun plain(text: String) = text.replace(CaptionLayout.RIGHT_TO_LEFT_MARK, "")

    private val long = (1..40).joinToString(" ") { "word$it" }

    @Test
    fun `the newest lines with text, the one still being written included`() {
        val lines = LockScreenCaptions.lines(listOf(line("one"), line("two"), line("  "), line("three", final = false))) { null }
        assertEquals(
            listOf(
                LockScreenCaptionLine(speaker = null, text = CaptionLayout.directed("two"), isFinal = true),
                LockScreenCaptionLine(speaker = null, text = CaptionLayout.directed("three"), isFinal = false),
            ),
            lines,
        )
        assertTrue(LockScreenCaptions.lines(emptyList()) { null }.isEmpty())
    }

    @Test
    fun `a line from before a quiet stretch doesn't sit above the words just said`() {
        fun said(text: String, at: Double) =
            TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, speakerClusterID = 1, startTimestamp = at, lastUpdateTimestamp = at + 3)
        val evening = said("אל תשכחי את הכדור בבוקר", 0.0)
        val morning = said("בוקר טוב", 8 * 3600.0)
        assertEquals(listOf(CaptionLayout.directed("בוקר טוב")), LockScreenCaptions.lines(listOf(evening, morning), name = names).map { it.text })

        val reply = said("בוקר אור", 8 * 3600.0 + 60)
        assertEquals(
            listOf(CaptionLayout.directed("בוקר טוב"), CaptionLayout.directed("בוקר אור")),
            LockScreenCaptions.lines(listOf(evening, morning, reply), name = names).map { it.text },
        )
    }

    @Test
    fun `a line ending in a Latin brand name reads right to left, its own mark included`() {
        val lines = LockScreenCaptions.lines(listOf(line("תתקשר ב-WhatsApp"))) { null }
        assertEquals(listOf(LockScreenCaptionLine(speaker = null, text = CaptionLayout.directed("תתקשר ב-WhatsApp"), isFinal = true)), lines)
        assertTrue(lines[0].text.startsWith(CaptionLayout.RIGHT_TO_LEFT_MARK))
    }

    @Test
    fun `the top line always says who is talking, and below it a name shows only where the speaker changes`() {
        assertEquals(listOf("Speaker 1", "Speaker 2"), LockScreenCaptions.lines(listOf(line("a", 1), line("b", 1), line("c", 2)), name = names).map { it.speaker })
        assertEquals(listOf("Speaker 1", null), LockScreenCaptions.lines(listOf(line("a", 2), line("b", 1), line("c", 1)), name = names).map { it.speaker })
        assertEquals(listOf("Speaker 1", null), LockScreenCaptions.lines(listOf(line("a", 1), line("b")), name = names).map { it.speaker })
    }

    @Test
    fun `a line shown alone takes the name above only when it is the same speaker, never when nobody was recognised on it`() {
        val continuing = LockScreenCaptions.lines(listOf(line("a", 1), line("b", 1)), name = names)
        assertEquals(listOf("Speaker 1", null), continuing.map { it.speaker })
        assertEquals(listOf(false, true), continuing.map { it.sameSpeakerAsAbove })
        assertEquals(listOf(false, false), LockScreenCaptions.lines(listOf(line("a", 1), line("b")), name = names).map { it.sameSpeakerAsAbove })
        assertEquals(listOf(false, false), LockScreenCaptions.lines(listOf(line("a", 1), line("b", 2)), name = names).map { it.sameSpeakerAsAbove })
    }

    @Test
    fun `asked for one line, the newest comes with its speaker's name even mid-run`() {
        val one = LockScreenCaptions.lines(listOf(line("a", 1), line("b", 1), line("  ")), count = 1, name = names)
        assertEquals(listOf(LockScreenCaptionLine(speaker = "Speaker 1", text = CaptionLayout.directed("b"), isFinal = true)), one)
    }

    @Test
    fun `long lines are cut to what fits, the newest line with more room than the one before, and a name takes its share`() {
        val lines = LockScreenCaptions.lines(listOf(line(long), line(long))) { null }
        assertEquals(2, lines.size)
        assertTrue(plain(lines[0].text).length <= LockScreenTextSize.Regular.earlierLineMaximumCharacters)
        assertTrue(plain(lines[1].text).length <= LockScreenTextSize.Regular.newestLineMaximumCharacters)
        assertTrue(plain(lines[1].text).length > LockScreenTextSize.Regular.earlierLineMaximumCharacters)
        assertTrue(lines.all { it.text.endsWith("word40") })

        val named = LockScreenCaptions.lines(listOf(line(long, 1)), name = names)
        assertEquals("Speaker 1", named[0].speaker)
        assertTrue(plain(named[0].text).length + "Speaker 1: ".length <= LockScreenTextSize.Regular.newestLineMaximumCharacters)

        val crowded = LockScreenCaptions.lines(listOf(line(long))) { "n".repeat(90) }
        assertEquals(CaptionLayout.directed(LockScreenCaptions.tail(long, LockScreenCaptions.MINIMUM_CHARACTERS)), crowded[0].text)
    }

    @Test
    fun `a short earlier line hands its unused room to the newest line instead of going to waste`() {
        val short = LockScreenCaptions.lines(listOf(line("hi"), line(long))) { null }
        assertTrue(plain(short[1].text).length > LockScreenTextSize.Regular.newestLineMaximumCharacters)
        val full = LockScreenCaptions.lines(listOf(line(long), line(long))) { null }
        assertTrue(plain(short[1].text).length > plain(full[1].text).length)
    }

    @Test
    fun `an earlier line longer than its room hands the newest none, and a name costs its own length and the colon after it`() {
        // With eight-letter words a word starts right next to both cuts
        // below, so one character more or less room shows or drops a word.
        val words = List(12) { "tomorrow" }.joinToString(" ")
        val budget = LockScreenTextSize.Regular.newestLineMaximumCharacters
        val full = LockScreenCaptions.lines(listOf(line(long), line(words))) { null }
        assertEquals(CaptionLayout.directed(LockScreenCaptions.tail(words, budget)), full[1].text)

        val rachel = LockScreenCaptions.lines(listOf(line(words))) { "Rachel" }
        assertEquals(CaptionLayout.directed(LockScreenCaptions.tail(words, budget - "Rachel: ".length)), rachel[0].text)

        val short = List(25) { "good" }.joinToString(" ")
        val unnamed = LockScreenCaptions.lines(listOf(line(long), line(short))) { null }
        assertEquals(CaptionLayout.directed(LockScreenCaptions.tail(short, budget)), unnamed[1].text)
    }

    @Test
    fun `the newest line never outgrows three rows, counting the name it shows or gets back when shown alone`() {
        for ((size, threeRows) in listOf(LockScreenTextSize.Regular to 3 * 30, LockScreenTextSize.Large to 3 * 23)) {
            val newSpeaker = LockScreenCaptions.lines(listOf(line("hi", 1), line(long, 2)), textSize = size, name = names)
            assertEquals("Speaker 2", newSpeaker[1].speaker)
            assertTrue(plain(newSpeaker[1].text).length + "Speaker 2: ".length <= threeRows, "$size")
            assertTrue(newSpeaker[1].text.endsWith("word40"))

            val sameSpeaker = LockScreenCaptions.lines(listOf(line("hi", 1), line(long, 1)), textSize = size, name = names)
            assertEquals(null, sameSpeaker[1].speaker)
            assertTrue(sameSpeaker[1].sameSpeakerAsAbove)
            assertTrue(plain(sameSpeaker[1].text).length + "Speaker 1: ".length <= threeRows, "$size")

            val unknown = LockScreenCaptions.lines(listOf(line("hi"), line(long)), textSize = size) { null }
            assertTrue(plain(unknown[1].text).length <= threeRows, "$size")
        }
    }

    @Test
    fun `large lock screen text keeps fewer characters, and follows the caption size in the app`() {
        val lines = LockScreenCaptions.lines(listOf(line(long), line(long)), textSize = LockScreenTextSize.Large) { null }
        assertTrue(plain(lines[0].text).length <= LockScreenTextSize.Large.earlierLineMaximumCharacters)
        assertTrue(plain(lines[1].text).length <= LockScreenTextSize.Large.newestLineMaximumCharacters)
        assertTrue(plain(lines[1].text).length > LockScreenTextSize.Regular.earlierLineMaximumCharacters)
        assertTrue(LockScreenTextSize.Large.newestLineMaximumCharacters < LockScreenTextSize.Regular.newestLineMaximumCharacters)

        assertEquals(LockScreenTextSize.Regular, LockScreenTextSize.of(AppSettings.default.display.fontSize))
        assertEquals(LockScreenTextSize.Large, LockScreenTextSize.of(LockScreenTextSize.LARGE_FROM_CAPTION_SIZE))
        assertEquals(LockScreenTextSize.Large, LockScreenTextSize.of(64.0))
    }

    @Test
    fun `quiet for a minute says how long ago, and for a quarter of an hour shows no lines`() {
        assertEquals(LockScreenCaptions.Quiet.Recent, LockScreenCaptions.quiet(newestLineAt = null, now = 1000.0))
        assertEquals(LockScreenCaptions.Quiet.Recent, LockScreenCaptions.quiet(newestLineAt = 1000.0, now = 1059.0))
        assertEquals(LockScreenCaptions.Quiet.MinutesAgo(1), LockScreenCaptions.quiet(newestLineAt = 1000.0, now = 1060.0))
        assertEquals(LockScreenCaptions.Quiet.MinutesAgo(14), LockScreenCaptions.quiet(newestLineAt = 1000.0, now = 1000.0 + 14 * 60 + 59))
        assertEquals(LockScreenCaptions.Quiet.Over, LockScreenCaptions.quiet(newestLineAt = 1000.0, now = 1000.0 + 15 * 60))
        // A clock that went back shows the lines rather than hiding them.
        assertEquals(LockScreenCaptions.Quiet.Recent, LockScreenCaptions.quiet(newestLineAt = 1000.0, now = 900.0))
        Localization.withLanguage(UILanguage.Hebrew) { assertEquals("נאמר לפני שתי דקות", LockScreenCaptions.ageNote(2)) }
        Localization.withLanguage(UILanguage.English) { assertEquals("said 2 minutes ago", LockScreenCaptions.ageNote(2)) }
    }

    @Test
    fun `a long line keeps its newest words, from a word boundary, marked as cut`() {
        val tail = LockScreenCaptions.tail((1..60).joinToString(" ") { "word$it" }, 40)
        assertTrue(tail.startsWith("…word"))
        assertTrue(tail.endsWith("word60"))
        assertTrue(tail.length <= 40)
        assertEquals("short", LockScreenCaptions.tail(" short ", 40))
    }

    @Test
    fun `a cut that lands mid-word skips to the next word, however long that word is`() {
        assertEquals("…of the plan tomorrow morning", LockScreenCaptions.tail("we need to talk about everyone's misunderstanding of the plan tomorrow morning", 45))
    }

    @Test
    fun `a cut that already lands on a word boundary keeps that word, even if it's short`() {
        val text = "we need to talk about the big report due on friday for the client meeting and also review notes from yesterday before lunch time today"
        assertTrue(LockScreenCaptions.tail(text, 45).startsWith("…notes"))
    }

    @Test
    fun `a cut never splits a character drawn from two UTF-16 units`() {
        assertEquals("…" + "👍".repeat(9), LockScreenCaptions.tail("👍".repeat(30), 10))
    }
}

class LockScreenUpdateThrottleTest {
    private val first = LockScreenCaptionContent(lines = listOf(LockScreenCaptionLine(speaker = null, text = "hello", isFinal = false)))
    private val second = LockScreenCaptionContent(lines = listOf(LockScreenCaptionLine(speaker = null, text = "hello there", isFinal = false)))

    @Test
    fun `the first change goes at once, a quick second one waits out the interval, an unchanged one isn't sent`() {
        val throttle = LockScreenUpdateThrottle(minimumInterval = 1.0)
        assertEquals(LockScreenUpdateThrottle.Decision.Send, throttle.decide(first, now = 100.0))
        throttle.sent(first, at = 100.0)
        assertEquals(LockScreenUpdateThrottle.Decision.NothingNew, throttle.decide(first, now = 100.2))
        assertEquals(LockScreenUpdateThrottle.Decision.Wait(0.75), throttle.decide(second, now = 100.25))
        assertEquals(LockScreenUpdateThrottle.Decision.Send, throttle.decide(second, now = 101.0))
        throttle.sent(second, at = 101.0)
        throttle.reset()
        assertEquals(LockScreenUpdateThrottle.Decision.Send, throttle.decide(second, now = 101.1))
        throttle.sent(second, at = 101.1)
        assertEquals(LockScreenUpdateThrottle.Decision.Send, throttle.decide(second.copy(status = "stopped"), now = 105.0))
    }

    @Test
    fun `a new status goes at once, even inside the interval`() {
        val throttle = LockScreenUpdateThrottle(minimumInterval = 15.0)
        throttle.sent(first, at = 100.0)
        assertEquals(LockScreenUpdateThrottle.Decision.Wait(14.0), throttle.decide(second, now = 101.0))
        val paused = first.copy(status = "paused")
        assertEquals(LockScreenUpdateThrottle.Decision.Send, throttle.decide(paused, now = 101.0))
        throttle.sent(paused, at = 101.0)
        assertEquals(LockScreenUpdateThrottle.Decision.Wait(14.0), throttle.decide(second.copy(status = "paused"), now = 102.0))
        assertEquals(LockScreenUpdateThrottle.Decision.Send, throttle.decide(first, now = 102.0))
    }

    @Test
    fun `a clock that went backwards sends rather than waiting for ever`() {
        val throttle = LockScreenUpdateThrottle(minimumInterval = 1.0)
        throttle.sent(first, at = 500.0)
        assertEquals(LockScreenUpdateThrottle.Decision.Send, throttle.decide(second, now = 10.0))
    }
}
