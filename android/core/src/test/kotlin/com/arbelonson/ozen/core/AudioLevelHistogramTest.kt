package com.arbelonson.ozen.core

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioLevelHistogramTest {
    private fun rms(decibels: Double): Float = 10.0.pow(decibels / 20).toFloat()

    @Test
    fun `percentiles come from the levels added, to the top of their 2 dB step`() {
        val histogram = AudioLevelHistogram()
        assertNull(histogram.decibels(atFraction = 0.5))
        assertNull(histogram.summary)
        repeat(10) { histogram.add(rms(-71.0)) }
        repeat(80) { histogram.add(rms(-57.0)) }
        repeat(10) { histogram.add(rms(-43.0)) }
        assertEquals(100, histogram.total)
        assertEquals(-70, histogram.decibels(atFraction = 0.1))
        assertEquals(-56, histogram.decibels(atFraction = 0.5))
        assertEquals(-42, histogram.decibels(atFraction = 0.95))
        assertEquals("quiet -70 / middle -56 / loud -56 dBFS", histogram.summary)
    }

    @Test
    fun `silence and clipping land in the end steps instead of crashing`() {
        val histogram = AudioLevelHistogram()
        histogram.add(0f)
        histogram.add(1e-9f)
        histogram.add(4f)
        histogram.add(Float.NaN)
        assertEquals(4, histogram.total)
        assertEquals(-98, histogram.decibels(atFraction = 0.0))
        assertEquals(-98, histogram.decibels(atFraction = 0.75))
        assertEquals(0, histogram.decibels(atFraction = 1.0))
    }
}
