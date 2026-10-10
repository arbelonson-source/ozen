package com.arbelonson.ozen.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

class FlowAudioFanOutTest {
    @Test
    fun `every output receives every chunk, in order, independently`() = runTest {
        val source = Channel<FloatArray>(Channel.UNLIMITED)
        val fan = FlowAudioFanOut(backgroundScope, source.receiveAsFlow(), count = 3)

        source.trySend(floatArrayOf(1f))
        source.trySend(floatArrayOf(2f, 2f))
        source.trySend(floatArrayOf(3f, 3f, 3f))
        source.close()

        for (output in fan.outputs) {
            val received = output.toList().map { it.toList() }
            assertEquals(listOf(listOf(1f), listOf(2f, 2f), listOf(3f, 3f, 3f)), received)
        }
    }

    @Test
    fun `a glitched sample, NaN or infinite, reaches every output as silence, the rest of the chunk is untouched`() = runTest {
        val source = Channel<FloatArray>(Channel.UNLIMITED)
        val glitches = AtomicInteger()
        val fan = FlowAudioFanOut(backgroundScope, source.receiveAsFlow(), count = 2, onGlitch = { glitches.incrementAndGet() })

        source.trySend(floatArrayOf(0.5f, Float.NaN, -0.25f))
        source.trySend(floatArrayOf(Float.POSITIVE_INFINITY, 0.125f, Float.NEGATIVE_INFINITY))
        source.trySend(floatArrayOf(0.75f))
        source.close()

        for (output in fan.outputs) {
            val received = output.toList().map { it.toList() }
            assertEquals(listOf(listOf(0.5f, 0f, -0.25f), listOf(0f, 0.125f, 0f), listOf(0.75f)), received)
        }
        assertEquals(2, glitches.get())
    }

    @Test
    fun `a slow consumer does not lose chunks while the fast one races ahead`() = runTest {
        val source = Channel<FloatArray>(Channel.UNLIMITED)
        val fan = FlowAudioFanOut(backgroundScope, source.receiveAsFlow(), count = 2)
        for (i in 0 until 50) {
            source.trySend(floatArrayOf(i.toFloat()))
        }
        source.close()

        var fastCount = 0
        fan.outputs[0].collect { fastCount += 1 }
        assertEquals(50, fastCount)

        var slowSum = 0f
        fan.outputs[1].collect { chunk ->
            delay(50.microseconds)
            slowSum += chunk[0]
        }
        assertEquals((0 until 50).sum().toFloat(), slowSum)
    }

    @Test
    fun `an output nobody reads keeps only the newest audio, so it can't grow without end`() = runTest {
        val source = Channel<FloatArray>(Channel.UNLIMITED)
        val fan = FlowAudioFanOut(backgroundScope, source.receiveAsFlow(), count = 2, bufferLimit = 10)
        for (i in 0 until 25) {
            source.trySend(floatArrayOf(i.toFloat()))
        }
        source.close()

        // Draining the first output waits for every chunk to have been
        // handed to both.
        fan.outputs[0].collect {}

        val abandoned = fan.outputs[1].toList().map { it[0] }
        assertEquals((15 until 25).map { it.toFloat() }, abandoned)
    }

    @Test
    fun `cancelling ends every output while the source is still open`() = runTest {
        val input = Channel<FloatArray>(Channel.UNLIMITED)
        val fan = FlowAudioFanOut(backgroundScope, input.receiveAsFlow(), count = 2)
        fan.cancel()
        val ended = withTimeoutOrNull(5.seconds) {
            for (output in fan.outputs) {
                output.collect {}
            }
            true
        } ?: false
        input.close()
        assertTrue(ended)
    }

    @Test
    fun `a count below one still yields a single usable output`() = runTest {
        val source = Channel<FloatArray>(Channel.UNLIMITED)
        assertEquals(1, FlowAudioFanOut(backgroundScope, source.receiveAsFlow(), count = 0).outputs.size)
    }
}
