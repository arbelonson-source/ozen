package com.arbelonson.ozen.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioFanOutTest {
    private fun chunks(output: AudioFanOut.Output): List<List<Float>> = output.drain().map { it.toList() }

    @Test
    fun `every output receives every chunk, in order, independently`() {
        val fan = AudioFanOut(count = 3)

        fan.push(floatArrayOf(1f))
        fan.push(floatArrayOf(2f, 2f))
        fan.push(floatArrayOf(3f, 3f, 3f))
        fan.finish()

        for (output in fan.outputs) {
            assertEquals(listOf(listOf(1f), listOf(2f, 2f), listOf(3f, 3f, 3f)), chunks(output))
        }
    }

    @Test
    fun `a glitched sample, NaN or infinite, reaches every output as silence - the rest of the chunk is untouched`() {
        val glitches = AtomicInteger()
        val fan = AudioFanOut(count = 2, onGlitch = { glitches.incrementAndGet() })

        fan.push(floatArrayOf(0.5f, Float.NaN, -0.25f))
        fan.push(floatArrayOf(Float.POSITIVE_INFINITY, 0.125f, Float.NEGATIVE_INFINITY))
        fan.push(floatArrayOf(0.75f))
        fan.finish()

        for (output in fan.outputs) {
            assertEquals(listOf(listOf(0.5f, 0f, -0.25f), listOf(0f, 0.125f, 0f), listOf(0.75f)), chunks(output))
        }
        assertEquals(2, glitches.get())
    }

    @Test
    fun `a slow consumer does not lose chunks while the fast one races ahead`() {
        val fan = AudioFanOut(count = 2)
        for (i in 0 until 50) {
            fan.push(floatArrayOf(i.toFloat()))
        }
        fan.finish()

        var fastCount = 0
        while (fan.outputs[0].take() != null) fastCount += 1
        assertEquals(50, fastCount)

        var slowSum = 0f
        while (true) {
            val chunk = fan.outputs[1].take() ?: break
            Thread.sleep(0, 50_000)
            slowSum += chunk[0]
        }
        assertEquals((0 until 50).sum().toFloat(), slowSum)
    }

    @Test
    fun `an output nobody reads keeps only the newest audio, so it can't grow without end`() {
        val fan = AudioFanOut(count = 2, bufferLimit = 10)
        for (i in 0 until 25) {
            fan.push(floatArrayOf(i.toFloat()))
        }
        fan.finish()

        fan.outputs[0].drain()

        val abandoned = fan.outputs[1].drain().map { it[0] }
        assertEquals((15 until 25).map { it.toFloat() }, abandoned)
    }

    @Test
    fun `cancelling ends every output while the source is still open`() {
        val fan = AudioFanOut(count = 2)
        fan.cancel()
        var ended = false
        val reader = Thread {
            for (output in fan.outputs) output.drain()
            ended = true
        }
        reader.start()
        reader.join(5_000)
        assertTrue(ended)
        fan.push(floatArrayOf(1f))
        assertTrue(fan.outputs.all { it.isFinished })
    }

    @Test
    fun `a count below one still yields a single usable output`() {
        assertEquals(1, AudioFanOut(count = 0).outputs.size)
    }

    @Test
    fun `chunks pushed from another thread reach a blocked reader`() {
        val fan = AudioFanOut(count = 1)
        var received: FloatArray? = null
        val reader = Thread { received = fan.outputs[0].take() }
        reader.start()
        fan.push(floatArrayOf(7f))
        reader.join(5_000)
        assertContentEquals(floatArrayOf(7f), received)
    }
}
