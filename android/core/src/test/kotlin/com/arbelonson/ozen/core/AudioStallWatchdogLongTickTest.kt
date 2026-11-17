package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioStallWatchdogLongTickTest {
    @Test
    fun `four ticks of elapsed time count as four and the stall is reported once on the call that crosses the limit`() {
        val watchdog = AudioStallWatchdog(stallSeconds = 6.0)
        val fired = List(9) { watchdog.tick(chunksReceived = 3, systemInterrupted = false, elapsedTicks = 4) }
        assertEquals(listOf(false, false, false, false, false, false, true, false, false), fired)
    }

    @Test
    fun `switching between one tick and four tick calls mid stall neither reports early nor misses the stall`() {
        val watchdog = AudioStallWatchdog(stallSeconds = 6.0)
        watchdog.tick(chunksReceived = 1, systemInterrupted = false, elapsedTicks = 4)
        var quarterTicks = 0
        var firedAt: Int? = null
        for (step in listOf(4, 4, 1, 1, 1, 1, 1, 1, 4, 4, 4, 4, 4, 4)) {
            if (firedAt != null) break
            quarterTicks += step
            if (watchdog.tick(chunksReceived = 1, systemInterrupted = false, elapsedTicks = step)) firedAt = quarterTicks
        }
        assertNotNull(firedAt)
        assertTrue(firedAt >= 24)
        assertTrue(firedAt < 28)
    }

    @Test
    fun `a call that covers no time never completes a stall and elapsed time converts to whole ticks`() {
        val watchdog = AudioStallWatchdog(stallSeconds = 0.5)
        watchdog.tick(chunksReceived = 1, systemInterrupted = false, elapsedTicks = 0)
        assertFalse(watchdog.tick(chunksReceived = 1, systemInterrupted = false, elapsedTicks = 0))
        assertEquals(4, watchdog.ticksForElapsed(1.0))
        assertEquals(1, watchdog.ticksForElapsed(0.49))
        assertEquals(0, watchdog.ticksForElapsed(-1.0))
    }
}
