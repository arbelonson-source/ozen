package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SliderStopsTest {
    private val separation = SliderStops.speakerSeparation

    @Test
    fun `a screen reader crosses the speaker separation and speech rate sliders in at most 16 swipes`() {
        val swipes = separation.steps + 1
        assertTrue(swipes <= 16, "TalkBack needs $swipes swipes to cross the speaker separation slider")
        assertTrue(SliderStops.speechRate.steps + 1 <= 16, "TalkBack needs ${SliderStops.speechRate.steps + 1} swipes to cross the speech rate slider")
    }

    @Test
    fun `every speaker separation stop is a round twentieth and the ends and the usual setting are stops`() {
        val values = separation.values
        assertTrue(values.all { abs(it * 20 - Math.round(it * 20)) < 0.001f }, "stops off the 0.05 grid: $values")
        assertEquals(0.2f, values.first(), 0.0001f)
        assertEquals(0.95f, values.last(), 0.0001f)
        val usual = AppSettings.default.speakerSimilarityThreshold
        assertTrue(values.any { abs(it - usual) < 0.0001f }, "the usual $usual is not a stop: $values")
    }

    @Test
    fun `a separation let go between stops is kept on the nearest one`() {
        assertEquals(0.45f, separation.kept(0.4512f), 0.0001f)
        assertEquals(0.6f, separation.kept(0.6123f), 0.0001f)
        assertEquals(0.65f, separation.kept(0.6301f), 0.0001f)
    }
}
