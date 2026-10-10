package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioStallWatchdogTest {
    @Test
    fun `the stall window is rounded up to whole ticks`() {
        assertEquals(24, AudioStallWatchdog(stallSeconds = 6.0).stallTicks)
        assertEquals(2, AudioStallWatchdog(stallSeconds = 0.3).stallTicks)
        assertEquals(1, AudioStallWatchdog(stallSeconds = 0.0).stallTicks)
    }

    @Test
    fun `it fires on the tick that completes the window, and only once`() {
        val watchdog = AudioStallWatchdog(stallSeconds = 0.75)
        val fired = (0 until 6).map { watchdog.tick(chunksReceived = 10, systemInterrupted = false) }
        assertEquals(listOf(false, false, false, true, false, false), fired)
    }

    @Test
    fun `any new chunk starts the count over`() {
        val watchdog = AudioStallWatchdog(stallSeconds = 0.75)
        var count = 0
        repeat(20) {
            count += 1
            watchdog.tick(chunksReceived = count, systemInterrupted = false)
            watchdog.tick(chunksReceived = count, systemInterrupted = false)
            val fired = watchdog.tick(chunksReceived = count, systemInterrupted = false)
            assertFalse(fired)
        }
    }

    @Test
    fun `a phone call holding the audio session is not a stall, and the count restarts after it`() {
        val watchdog = AudioStallWatchdog(stallSeconds = 0.75)
        watchdog.tick(chunksReceived = 5, systemInterrupted = false)
        watchdog.tick(chunksReceived = 5, systemInterrupted = false)
        val duringCall = (0 until 40).map { watchdog.tick(chunksReceived = 5, systemInterrupted = true) }
        assertFalse(duringCall.contains(true))
        val afterCall = (0 until 4).map { watchdog.tick(chunksReceived = 5, systemInterrupted = false) }
        assertEquals(listOf(false, false, true, false), afterCall)
    }

    @Test
    fun `reset forgets earlier quiet ticks`() {
        val watchdog = AudioStallWatchdog(stallSeconds = 0.5)
        watchdog.tick(chunksReceived = 1, systemInterrupted = false)
        watchdog.tick(chunksReceived = 1, systemInterrupted = false)
        watchdog.reset()
        val fired = (0 until 3).map { watchdog.tick(chunksReceived = 1, systemInterrupted = false) }
        assertEquals(listOf(false, false, true), fired)
    }

    @Test
    fun `a disabled watchdog never fires`() {
        val watchdog = AudioStallWatchdog.disabled
        var fired = false
        repeat(1_000) {
            fired = watchdog.tick(chunksReceived = 0, systemInterrupted = false) || fired
        }
        assertFalse(fired)
        assertTrue(!watchdog.isEnabled)
    }
}
