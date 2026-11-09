package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UtteranceCutTest {
    private fun speech(count: Int, gap: IntRange? = null): FloatArray = FloatArray(count) { index ->
        when {
            gap != null && index in gap -> 0f
            index % 2 == 0 -> 0.5f
            else -> -0.5f
        }
    }

    @Test
    fun `the cut goes into the quiet between words, not wherever the counter stopped`() {
        val samples = speech(count = 16_000, gap = 12_000 until 12_800)
        val cut = UtteranceCut.quietestPoint(samples, before = 16_000, lookBack = 8_000, frame = 400)
        assertEquals(12_600, cut)
    }

    @Test
    fun `a gap older than the look-back is not reached for`() {
        val samples = speech(count = 16_000, gap = 1_000 until 1_800)
        val cut = UtteranceCut.quietestPoint(samples, before = 16_000, lookBack = 4_000, frame = 400)
        assertTrue(cut >= 12_000)
    }

    @Test
    fun `with nothing quieter anywhere, the latest point wins so little carries over`() {
        val samples = speech(count = 16_000)
        val cut = UtteranceCut.quietestPoint(samples, before = 16_000, lookBack = 4_000, frame = 400)
        assertTrue(cut >= 15_000)
        assertTrue(cut <= 16_000)
    }

    @Test
    fun `too little audio, or nonsense arguments, leave the cut where it was asked for`() {
        assertEquals(100, UtteranceCut.quietestPoint(speech(count = 100), before = 100, lookBack = 4_000, frame = 400))
        assertEquals(0, UtteranceCut.quietestPoint(FloatArray(0), before = 50, lookBack = 10, frame = 4))
        assertEquals(1_000, UtteranceCut.quietestPoint(speech(count = 1_000), before = 5_000, lookBack = 1_000, frame = 0))
        assertEquals(0, UtteranceCut.quietestPoint(speech(count = 1_000), before = -3, lookBack = 1_000, frame = 100))
    }

    @Test
    fun `a finished line reaches no further than the cap, whether someone was still talking, paused or stopped`() {
        assertEquals(FinishedLine(65, false), UtteranceCut.finishedLine(total = 100, speechEnd = 60, pad = 5, maxSamples = 280, stillTalkingAtCap = false))
        assertEquals(FinishedLine(62, false), UtteranceCut.finishedLine(total = 62, speechEnd = 60, pad = 5, maxSamples = 280, stillTalkingAtCap = false))
        assertEquals(FinishedLine(280, true), UtteranceCut.finishedLine(total = 290, speechEnd = 289, pad = 5, maxSamples = 280, stillTalkingAtCap = true))
        assertEquals(FinishedLine(265, true), UtteranceCut.finishedLine(total = 270, speechEnd = 260, pad = 5, maxSamples = 280, stillTalkingAtCap = true))
        assertEquals(FinishedLine(280, true), UtteranceCut.finishedLine(total = 400, speechEnd = 390, pad = 5, maxSamples = 280, stillTalkingAtCap = false))
    }

    private fun lineEnd(pauses: List<Int>, speechEnd: Int, total: Int, maxSamples: Int = 280, finished: Boolean = false): Int? =
        UtteranceCut.lineEndAtPause(pauses, speechEnd, total, pause = 7, pad = 3, maxSamples = maxSamples, mostWaiting = 2, finished = finished)

    @Test
    fun `a line ends at the first pause heard, even one a slow pass hid while the next sentence began`() {
        assertEquals(null, lineEnd(emptyList(), speechEnd = 60, total = 66))
        assertEquals(60, lineEnd(emptyList(), speechEnd = 60, total = 67))
        assertEquals(40, lineEnd(listOf(40), speechEnd = 60, total = 62))
        assertEquals(40, lineEnd(listOf(40), speechEnd = 60, total = 70))
        assertEquals(20, lineEnd(listOf(20, 40), speechEnd = 60, total = 62))
    }

    @Test
    fun `more than two finished sentences waiting share one line, as far as the longest line reaches`() {
        assertEquals(60, lineEnd(listOf(20, 40), speechEnd = 60, total = 70))
        assertEquals(50, lineEnd(listOf(20, 40, 50), speechEnd = 60, total = 62))
        assertEquals(40, lineEnd(listOf(20, 40, 50), speechEnd = 60, total = 62, maxSamples = 45))
        assertEquals(20, lineEnd(listOf(20, 40, 50), speechEnd = 60, total = 62, maxSamples = 22))
    }

    @Test
    fun `when the captions stop before the last pause, the last sentence counts as waiting too`() {
        assertEquals(60, lineEnd(emptyList(), speechEnd = 60, total = 62, finished = true))
        assertEquals(40, lineEnd(listOf(40), speechEnd = 60, total = 62, finished = true))
        assertEquals(60, lineEnd(listOf(20, 40), speechEnd = 60, total = 62, finished = true))
        assertEquals(40, lineEnd(listOf(20, 40), speechEnd = 60, total = 62, maxSamples = 45, finished = true))
    }
}
