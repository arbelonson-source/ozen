package com.arbelonson.ozen.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleRateConverterTest {
    private fun tone(hertz: Double, rate: Int, seconds: Double = 1.0, amplitude: Double = 0.5) =
        FloatArray((rate * seconds).toInt()) { (amplitude * sin(2 * PI * hertz * it / rate)).toFloat() }

    private fun middle(samples: FloatArray) = samples.copyOfRange(samples.size / 4, samples.size * 3 / 4)

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun hertz(samples: FloatArray, rate: Int): Double {
        val part = middle(samples)
        val crossings = (1 until part.size).count { part[it - 1] < 0f && part[it] >= 0f }
        return crossings * rate.toDouble() / part.size
    }

    @Test
    fun `a recording already at 16 kHz comes out as it went in`() {
        val voice = tone(440.0, 16_000)
        assertContentEquals(voice, SampleRateConverter.convert(voice, from = 16_000))
    }

    @Test
    fun `a tone recorded at 44,1 kHz keeps its length, pitch and loudness at 16 kHz`() {
        val converted = SampleRateConverter.convert(tone(1_000.0, 44_100), from = 44_100)
        assertTrue(abs(converted.size - 16_000) <= 1, "${converted.size} samples")
        assertTrue(abs(hertz(converted, 16_000) - 1_000.0) < 5.0, "${hertz(converted, 16_000)} Hz")
        assertTrue(abs(rms(middle(converted)) - 0.5 / sqrt(2.0)) < 0.01, "rms ${rms(middle(converted))}")
    }

    @Test
    fun `a 12 kHz whistle at 48 kHz, too high for 16 kHz, is filtered out instead of folding down to 4 kHz`() {
        val converted = SampleRateConverter.convert(tone(12_000.0, 48_000), from = 48_000)
        assertTrue(abs(converted.size - 16_000) <= 1, "${converted.size} samples")
        assertTrue(rms(middle(converted)) < 0.5 / sqrt(2.0) / 100, "rms ${rms(middle(converted))}")
    }

    @Test
    fun `a voice just under 8 kHz at 48 kHz is kept`() {
        val converted = SampleRateConverter.convert(tone(3_000.0, 48_000), from = 48_000)
        assertTrue(abs(rms(middle(converted)) - 0.5 / sqrt(2.0)) < 0.01, "rms ${rms(middle(converted))}")
        assertTrue(abs(hertz(converted, 16_000) - 3_000.0) < 10.0, "${hertz(converted, 16_000)} Hz")
    }

    @Test
    fun `8 kHz phone audio is brought up to 16 kHz at the same pitch and loudness`() {
        val converted = SampleRateConverter.convert(tone(300.0, 8_000), from = 8_000)
        assertTrue(abs(converted.size - 16_000) <= 1, "${converted.size} samples")
        assertTrue(abs(hertz(converted, 16_000) - 300.0) < 3.0, "${hertz(converted, 16_000)} Hz")
        assertTrue(abs(rms(middle(converted)) - 0.5 / sqrt(2.0)) < 0.01, "rms ${rms(middle(converted))}")
    }

    @Test
    fun `an odd rate of 44,101 Hz converts like its neighbours`() {
        val converted = SampleRateConverter.convert(tone(1_000.0, 44_101), from = 44_101)
        assertTrue(abs(converted.size - 16_000) <= 1, "${converted.size} samples")
        assertTrue(abs(hertz(converted, 16_000) - 1_000.0) < 5.0, "${hertz(converted, 16_000)} Hz")
        assertTrue(abs(rms(middle(converted)) - 0.5 / sqrt(2.0)) < 0.01, "rms ${rms(middle(converted))}")
    }

    @Test
    fun `nothing in gives nothing out`() {
        assertEquals(0, SampleRateConverter.convert(FloatArray(0), from = 44_100).size)
    }
}
