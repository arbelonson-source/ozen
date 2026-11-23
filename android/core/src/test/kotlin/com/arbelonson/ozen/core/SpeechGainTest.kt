package com.arbelonson.ozen.core

import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val threadMemory = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

// The level as it was worked out before: every fourth sample's size,
// boxed one by one and sorted whole, on every pass.
private fun gainAsItWas(samples: FloatArray): Float {
    val magnitudes = ArrayList<Float>(samples.size / 4 + 1)
    var index = 0
    while (index < samples.size) {
        if (samples[index].isFinite()) magnitudes.add(abs(samples[index]))
        index += 4
    }
    if (magnitudes.isEmpty()) return 1f
    magnitudes.sort()
    val loud = magnitudes[min(magnitudes.size - 1, (magnitudes.size.toFloat() * 0.999f).toInt())]
    if (!(loud > 0)) return 1f
    return min(max(SpeechGain.TARGET_PEAK / loud, 1f), SpeechGain.MAXIMUM_GAIN)
}

private fun normalizedAsItWas(samples: FloatArray): FloatArray {
    val gain = gainAsItWas(samples)
    if (!(gain > 1)) return samples
    return FloatArray(samples.size) { min(max(samples[it] * gain, -1f), 1f) }
}

private val oddValues = floatArrayOf(
    0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
    Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 1f, -1f, 0.5f, 0.005f,
)

private fun edgeCases(): List<FloatArray> {
    val cases = ArrayList<FloatArray>()
    cases += FloatArray(0)
    for (value in oddValues) for (size in 1..9) cases += FloatArray(size) { value }
    for (size in 1..64) {
        cases += FloatArray(size)
        cases += FloatArray(size) { -0f }
        cases += FloatArray(size) { (it + 1) * 0.01f }
        cases += FloatArray(size) { (size - it) * -0.01f }
        cases += FloatArray(size) { if (it % 4 == 0) Float.NaN else 0.3f }
        cases += FloatArray(size) { if (it % 8 == 0) 0.2f else Float.POSITIVE_INFINITY }
    }
    for (size in listOf(999, 1_000, 1_001, 3_996, 4_000, 4_004, 40_000, 112_000, 448_000)) {
        cases += FloatArray(size) { it.toFloat() / size }
        cases += FloatArray(size) { (size - it).toFloat() / size * 0.1f }
        cases += FloatArray(size) { 0.1f }
        cases += FloatArray(size) { if (it % 4 == 0) 0.02f else 0.9f }
        cases += FloatArray(size) { 0.01f * sin(it * 0.05f) }.also { it[size / 2] = 0.9f }
    }
    return cases
}

private fun randomCase(random: Random): FloatArray {
    val size = when (random.nextInt(10)) {
        in 0..2 -> random.nextInt(0, 100)
        in 3..8 -> random.nextInt(100, 20_000)
        else -> random.nextInt(20_000, 120_000)
    }
    val level = 10f.pow(random.nextFloat() * 2.2f - 2.5f)
    val steps = if (random.nextBoolean()) 32_768f else 0f
    val odd = if (random.nextInt(4) == 0) random.nextFloat() * 0.05f else 0f
    return FloatArray(size) {
        val value = (random.nextFloat() * 2 - 1) * level * (if (random.nextInt(1_000) == 0) 10f else 1f)
        if (odd > 0 && random.nextFloat() < odd) oddValues[random.nextInt(oddValues.size)]
        else if (steps > 0) Math.round(value * steps) / steps
        else value
    }
}

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

    @Test
    fun `the level comes out exactly as it did when every sample looked at was sorted`() {
        val random = Random(20261123)
        val cases = edgeCases() + List(3_000) { randomCase(random) }
        val failures = ArrayList<String>()
        var raised = 0
        for ((number, samples) in cases.withIndex()) {
            val before = gainAsItWas(samples)
            val now = SpeechGain.gain(samples)
            if (before.toRawBits() != now.toRawBits()) failures += "case $number of ${samples.size} samples: gain $now, it was $before"
            if (before > 1f && before < SpeechGain.MAXIMUM_GAIN) raised += 1
        }
        assertEquals(emptyList(), failures.take(10), "${failures.size} of ${cases.size} cases differ")
        assertTrue(raised > cases.size / 3, "only $raised of ${cases.size} cases were raised by less than the most")
    }

    @Test
    fun `the raised audio comes out exactly as it did, and loud audio is still handed back as it is`() {
        val random = Random(7)
        for (samples in edgeCases() + List(300) { randomCase(random) }) {
            val before = normalizedAsItWas(samples)
            val now = SpeechGain.normalized(samples)
            assertEquals(before === samples, now === samples, "${samples.size} samples")
            assertTrue(before.size == now.size && before.indices.all { before[it].toRawBits() == now[it].toRawBits() }, "${samples.size} samples")
        }
    }

    // A live pass works out the level of the whole line again about every
    // 0.6 s. Boxing and sorting every fourth sample of a 28 s line made
    // 6 MB of garbage and took 30 ms on a laptop each time.
    @Test
    fun `working out the level of a whole line makes next to no garbage`() {
        assertTrue(threadMemory.isThreadAllocatedMemorySupported && threadMemory.isThreadAllocatedMemoryEnabled)
        val line = tone(peak = 0.05f, count = 28 * 16_000)
        SpeechGain.gain(line)
        val before = threadMemory.currentThreadAllocatedBytes
        repeat(TIMES) { SpeechGain.gain(line) }
        val each = (threadMemory.currentThreadAllocatedBytes - before) / TIMES
        assertTrue(each < LEVEL_BYTES_ALLOWED, "working out the level of 28 s of audio allocated $each bytes, $LEVEL_BYTES_ALLOWED allowed")
    }

    private companion object {
        const val TIMES = 5
        const val LEVEL_BYTES_ALLOWED = 4_096L
    }
}
