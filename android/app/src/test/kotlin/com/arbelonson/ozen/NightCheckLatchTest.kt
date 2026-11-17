package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightCheckLatchTest {
    @Test
    fun `sounds only handed over for a recording is not a night start`() {
        assertFalse(NightStartCheck.isNightStart(soundsOnly = true, pausedForRecording = true))
        assertTrue(NightStartCheck.isNightStart(soundsOnly = true, pausedForRecording = false))
        assertFalse(NightStartCheck.isNightStart(soundsOnly = false, pausedForRecording = false))
    }

    @Test
    fun `a night start is checked once until sounds only has been off`() {
        val latch = NightCheckLatch()
        latch.nightStart(true)
        assertTrue(latch.claim())
        assertFalse(latch.claim())
        latch.nightStart(true)
        assertFalse(latch.claim())
    }

    @Test
    fun `sounds only that went off and on while the screen was away is checked again`() {
        val latch = NightCheckLatch()
        assertTrue(latch.claim())
        latch.nightStart(false)
        latch.nightStart(true)
        assertTrue(latch.claim())
    }
}
