package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FRAME = 0.032

private fun frames(vararg runs: Pair<Boolean, Double>): FloatArray {
    val out = ArrayList<Float>()
    for ((speech, seconds) in runs) repeat(Math.round(seconds / FRAME).toInt()) { out.add(if (speech) 0.9f else 0.05f) }
    return out.toFloatArray()
}

private fun assertSeconds(expected: Double, actual: Double) = assertTrue(kotlin.math.abs(expected - actual) <= 3 * FRAME, "expected $expected, got $actual")

class SpeechStretchPlannerTest {
    @Test
    fun aFileOfSilenceGivesNothingToTranscribe() {
        assertTrue(SpeechStretchPlanner.plan(frames(false to 120.0)).isEmpty())
    }

    @Test
    fun anEmptyFileGivesNothing() {
        assertTrue(SpeechStretchPlanner.plan(FloatArray(0)).isEmpty())
    }

    @Test
    fun oneSentenceIsOneStretchFromItsFirstToItsLastSound() {
        val stretches = SpeechStretchPlanner.plan(frames(false to 2.0, true to 3.0, false to 2.0))
        assertEquals(1, stretches.size)
        assertSeconds(2.0, stretches[0].startSeconds)
        assertSeconds(5.0, stretches[0].endSeconds)
    }

    @Test
    fun aPauseOfAThirdOfASecondDoesNotSplitASentence() {
        val stretches = SpeechStretchPlanner.plan(frames(true to 2.0, false to 0.35, true to 2.0, false to 1.0))
        assertEquals(1, stretches.size)
        assertSeconds(0.0, stretches[0].startSeconds)
        assertSeconds(4.35, stretches[0].endSeconds)
    }

    @Test
    fun aPauseOfAFullSecondSplitsTwoSentences() {
        val stretches = SpeechStretchPlanner.plan(frames(true to 2.0, false to 1.0, true to 2.0, false to 1.0))
        assertEquals(2, stretches.size)
        assertSeconds(2.0, stretches[0].endSeconds)
        assertSeconds(3.0, stretches[1].startSeconds)
        assertSeconds(5.0, stretches[1].endSeconds)
    }

    @Test
    fun aSoundTooShortToBeSpeechIsDropped() {
        val stretches = SpeechStretchPlanner.plan(frames(false to 3.0, true to 0.1, false to 3.0, true to 2.0, false to 1.0))
        assertEquals(1, stretches.size)
        assertSeconds(6.1, stretches[0].startSeconds)
    }

    @Test
    fun speechRunningToTheEndOfTheFileIsKept() {
        val stretches = SpeechStretchPlanner.plan(frames(false to 1.0, true to 4.0))
        assertEquals(1, stretches.size)
        assertSeconds(5.0, stretches[0].endSeconds)
    }

    @Test
    fun noStretchIsLongerThanTheCapAndNoSpeechIsLost() {
        val stretches = SpeechStretchPlanner.plan(frames(true to 100.0))
        assertTrue(stretches.size >= 4)
        for (stretch in stretches) assertTrue(stretch.endSeconds - stretch.startSeconds <= 28.0 + 1e-9, "$stretch")
        for ((before, after) in stretches.zipWithNext()) assertTrue(after.startSeconds - before.endSeconds <= FRAME + 1e-9)
        assertSeconds(0.0, stretches.first().startSeconds)
        assertSeconds(100.0, stretches.last().endSeconds)
    }

    @Test
    fun aLongSentenceIsCutAtTheQuietestMomentNearTheCap() {
        val stretches = SpeechStretchPlanner.plan(frames(true to 26.0, false to 0.3, true to 20.0, false to 1.0))
        assertEquals(2, stretches.size)
        assertSeconds(26.0, stretches[0].endSeconds)
        assertSeconds(26.3, stretches[1].startSeconds)
        assertSeconds(46.3, stretches[1].endSeconds)
    }

    @Test
    fun theCapCanBeShorterWhenTheNamesListTakesRoom() {
        val stretches = SpeechStretchPlanner.plan(frames(true to 60.0), maxStretchSeconds = 12.0)
        for (stretch in stretches) assertTrue(stretch.endSeconds - stretch.startSeconds <= 12.0 + 1e-9)
        assertTrue(stretches.size >= 5)
    }

    @Test
    fun feedingTheScoresInSmallPiecesGivesTheSameStretchesAsAllAtOnce() {
        val all = frames(false to 1.0, true to 9.0, false to 1.0, true to 40.0, false to 0.4, true to 5.0, false to 2.0, true to 3.0)
        val planner = SpeechStretchPlanner()
        val pieces = ArrayList<SpeechStretch>()
        var at = 0
        while (at < all.size) {
            val next = minOf(all.size, at + 7)
            pieces += planner.add(all.copyOfRange(at, next))
            at = next
        }
        pieces += planner.finish()
        assertEquals(SpeechStretchPlanner.plan(all), pieces)
    }

    @Test
    fun aStretchIsOnlyHandedOverOnceThePauseAfterItIsHeard() {
        val planner = SpeechStretchPlanner()
        assertTrue(planner.add(frames(true to 3.0)).isEmpty())
        assertTrue(planner.add(frames(false to 0.3)).isEmpty())
        assertEquals(1, planner.add(frames(false to 0.6)).size)
    }

    @Test
    fun theOpenStretchSaysWhereAudioMustBeKeptFrom() {
        val planner = SpeechStretchPlanner()
        assertNull(planner.openStartSeconds)
        planner.add(frames(false to 2.0, true to 1.0))
        assertSeconds(2.0, planner.openStartSeconds!!)
        planner.add(frames(false to 1.0))
        assertNull(planner.openStartSeconds)
    }

    @Test
    fun aStretchCountsHowManyFramesOfItWereVoice() {
        val stretch = SpeechStretchPlanner.plan(frames(true to 1.0, false to 0.3, true to 1.0, false to 1.0)).single()
        assertTrue(stretch.voicedFrames in 60..64, "${stretch.voicedFrames}")
    }
}
