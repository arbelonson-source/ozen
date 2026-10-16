package com.arbelonson.ozen.whisper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class PhoneSpeedMeterTest {
    private fun meter(speeds: List<Double>, ready: Boolean = true, asked: MutableList<Pair<Int, Int>> = ArrayList()): PhoneSpeedMeter {
        val queue = ArrayDeque(speeds)
        return PhoneSpeedMeter(
            libraryDir = "/lib", threads = 3, rounds = speeds.size, roundMillis = 700, now = { 42.0 },
            ready = { ready },
            measure = { threads, millis -> asked += threads to millis; queue.removeFirst() },
        )
    }

    @Test
    fun `the result is the middle round, so one slow or lucky round does not decide it`() = runBlocking {
        val speed = meter(listOf(90.0, 10.0, 80.0, 85.0, 400.0)).run { }
        assertEquals(85.0, speed!!.gflops)
        assertEquals(3, speed.threads)
        assertEquals(42.0, speed.measuredAt)
    }

    @Test
    fun `every round runs on the engine's thread count for its time, and progress climbs from nothing to done`() = runBlocking {
        val asked = ArrayList<Pair<Int, Int>>()
        val shown = ArrayList<Float>()
        meter(listOf(50.0, 50.0, 50.0, 50.0), asked = asked).run { shown += it }
        assertEquals(List(4) { 3 to 700 }, asked)
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f), shown)
    }

    @Test
    fun `failed rounds are ignored, and no usable round or no library means no result`() = runBlocking {
        assertEquals(70.0, meter(listOf(0.0, 70.0, 0.0)).run { }!!.gflops)
        assertNull(meter(listOf(0.0, 0.0)).run { })
        assertNull(meter(listOf(60.0), ready = false).run { })
    }

    @Test
    fun `the test uses as many threads as the engine does`() {
        assertEquals(Runtime.getRuntime().availableProcessors().coerceIn(1, 4), PhoneSpeedMeter.engineThreads())
    }
}
