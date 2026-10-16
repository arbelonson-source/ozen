package com.arbelonson.ozen.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class SampleRateConverterDrainTest {
    private val tone = FloatArray(48_000 * 3) { (0.4 * sin(2 * PI * 440 * it / 48_000)).toFloat() }

    @Test
    fun drainingAsItGoesGivesTheSameSamplesAsConvertingAtOnce() {
        val stream = SampleRateConverter.Stream(48_000, 16_000)
        val parts = ArrayList<FloatArray>()
        tone.toList().chunked(7_001).forEach {
            stream.add(it.toFloatArray())
            parts.add(stream.drain())
        }
        parts.add(stream.finish())
        assertContentEquals(SampleRateConverter.convert(tone, from = 48_000, to = 16_000), parts.reduce { a, b -> a + b })
    }

    @Test
    fun whatWasDrainedIsNotHeldOnTo() {
        val stream = SampleRateConverter.Stream(44_100, 16_000)
        stream.add(tone)
        assertTrue(stream.drain().isNotEmpty())
        assertTrue(stream.drain().isEmpty())
    }
}
