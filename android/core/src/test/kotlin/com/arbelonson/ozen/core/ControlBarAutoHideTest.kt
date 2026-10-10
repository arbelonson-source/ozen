package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun hides(
    enabled: Boolean = true,
    isListening: Boolean = true,
    followingLatest: Boolean = true,
    hasLines: Boolean = true,
    voiceOverRunning: Boolean = false,
    pausedForCall: Boolean = false,
    idle: Double = 10.0,
): Boolean = ControlBarAutoHide.hides(
    enabled = enabled,
    isListening = isListening,
    followingLatest = followingLatest,
    hasLines = hasLines,
    voiceOverRunning = voiceOverRunning,
    pausedForCall = pausedForCall,
    lastTouchAt = 1_000.0,
    now = 1_000.0 + idle,
)

class ControlBarAutoHideTest {
    @Test
    fun `captions running on their own, untouched for a few seconds, the buttons go`() {
        assertTrue(hides())
        assertTrue(hides(idle = ControlBarAutoHide.IDLE_SECONDS))
    }

    @Test
    fun `a recent touch keeps them`() {
        assertFalse(hides(idle = ControlBarAutoHide.IDLE_SECONDS - 0.5))
        assertFalse(hides(idle = 0.0))
    }

    @Test
    fun `captions paused for a call keep the bar, which is where it says so and where the tap to try again is`() {
        assertFalse(hides(pausedForCall = true))
    }

    @Test
    fun `anything that needs the buttons keeps them, not listening, reading back, no lines yet, VoiceOver, switched off`() {
        val kept = listOf(
            hides(isListening = false),
            hides(followingLatest = false),
            hides(hasLines = false),
            hides(voiceOverRunning = true),
            hides(enabled = false),
        )
        assertEquals(listOf(false, false, false, false, false), kept)
    }
}
