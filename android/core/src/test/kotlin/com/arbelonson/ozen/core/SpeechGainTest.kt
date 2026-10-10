package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechGainTest {
    private fun tone(peak: Float, count: Int = 16_000): FloatArray =
        FloatArray(count) { peak * sin(it.toFloat() * 0.05f) }

    private fun peakOf(samples: FloatArray): Float = samples.maxOf { abs(it) }

    @Test
    fun `speech from across the room is brought up to a level a 16-bit file keeps`() {
        val raised = SpeechGain.normalized(tone(peak = 0.01f))
        val peak = peakOf(raised)
        assertTrue(peak > 0.45f && peak <= 0.55f)
    }

    @Test
    fun `speech only a little quiet is raised too, by less than double`() {
        val raised = SpeechGain.normalized(tone(peak = 0.4f))
        val peak = peakOf(raised)
        assertTrue(peak > 0.45f && peak <= 0.55f)
    }

    @Test
    fun `speech that is already loud is left as it is`() {
        val loud = tone(peak = 0.8f)
        assertContentEquals(loud, SpeechGain.normalized(loud))
    }

    @Test
    fun `one click doesn't decide the level, and doesn't leave the range once raised`() {
        val samples = tone(peak = 0.01f)
        samples[8_000] = 0.9f
        samples[12_000] = -0.9f
        val raised = SpeechGain.normalized(samples)
        assertEquals(1f, raised[8_000])
        assertEquals(-1f, raised[12_000])
        assertTrue(abs(raised[8_001]) < 0.6f)
        assertTrue(raised.map { abs(it) }.sorted()[raised.size / 2] > 0.2f)
    }

    @Test
    fun `the level is set by the loudest stretch, wherever in the window it falls`() {
        val gain = SpeechGain.gain(tone(peak = 0.1f, count = 8_000) + tone(peak = 0.001f, count = 8_000))
        assertTrue(gain > 4.5f && gain < 5.5f)
    }

    @Test
    fun `near silence isn't blown up into a roar`() {
        assertEquals(SpeechGain.MAXIMUM_GAIN, SpeechGain.gain(tone(peak = 0.00001f)))
        assertEquals(1f, SpeechGain.gain(FloatArray(100)))
        assertEquals(1f, SpeechGain.gain(FloatArray(0)))
        assertEquals(1f, SpeechGain.gain(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY)))
    }
}
