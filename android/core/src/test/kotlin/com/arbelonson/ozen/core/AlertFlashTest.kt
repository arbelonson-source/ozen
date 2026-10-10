package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertFlashTest {
    @Test
    fun `safety sounds flash longest, the door briefly, everyday sounds not at all`() {
        val critical = assertNotNull(AlertFlash.pattern(SoundEvent.Importance.Critical, reduceMotion = false))
        val high = assertNotNull(AlertFlash.pattern(SoundEvent.Importance.High, reduceMotion = false))
        assertTrue(critical.count > high.count)
        assertTrue(high.count >= 2)
        assertNull(AlertFlash.pattern(SoundEvent.Importance.Medium, reduceMotion = false))
        assertNull(AlertFlash.pattern(SoundEvent.Importance.Low, reduceMotion = true))
    }

    @Test
    fun `no pattern blinks fast enough to risk a seizure`() {
        for (importance in SoundEvent.Importance.entries) {
            for (reduceMotion in listOf(false, true)) {
                val flash = AlertFlash.pattern(importance, reduceMotion) ?: continue
                assertTrue(flash.flashesPerSecond < AlertFlash.MAXIMUM_FLASHES_PER_SECOND, "$importance reduceMotion=$reduceMotion")
                assertTrue(flash.litSeconds > 0)
            }
        }
    }

    @Test
    fun `the limit is the seizure guideline's three flashes a second, and a flash is one lit-and-dark cycle`() {
        assertTrue(AlertFlash.MAXIMUM_FLASHES_PER_SECOND <= 3)
        assertEquals(4.0, AlertFlash(litSeconds = 0.125, darkSeconds = 0.125, count = 8).flashesPerSecond)
    }

    @Test
    fun `a flash gives way only to an alert at least as important, and a dark screen to any that flashes`() {
        val high = SoundEvent.Importance.High
        val critical = SoundEvent.Importance.Critical
        assertTrue(AlertFlash.takesOver(null, high, reduceMotion = false))
        assertFalse(AlertFlash.takesOver(null, SoundEvent.Importance.Medium, reduceMotion = false))
        assertFalse(AlertFlash.takesOver(critical, high, reduceMotion = false))
        assertFalse(AlertFlash.takesOver(critical, SoundEvent.Importance.Low, reduceMotion = true))
        assertTrue(AlertFlash.takesOver(high, critical, reduceMotion = true))
        assertTrue(AlertFlash.takesOver(high, high, reduceMotion = false))
    }

    @Test
    fun `with Reduce Motion the edge lights once instead of blinking, for about as long`() {
        for (importance in listOf(SoundEvent.Importance.Critical, SoundEvent.Importance.High)) {
            val blinking = assertNotNull(AlertFlash.pattern(importance, reduceMotion = false))
            val steady = assertNotNull(AlertFlash.pattern(importance, reduceMotion = true))
            assertEquals(1, steady.count)
            val blinkingSeconds = blinking.count.toDouble() * (blinking.litSeconds + blinking.darkSeconds)
            assertTrue(abs(steady.litSeconds - blinkingSeconds) <= 1)
        }
    }
}
