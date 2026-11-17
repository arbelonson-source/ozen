package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuzzEchoTest {
    private val pattern = AlertVibration.pattern(SoundEvent.Importance.High)

    @Test
    fun `a knock while the motor runs is ignored`() {
        assertTrue(BuzzEcho.ignores(pattern, 100.0, 100.08))
    }

    @Test
    fun `a knock in a gap longer than the tail is heard`() {
        assertFalse(BuzzEcho.ignores(pattern, 100.0, 100.95))
    }

    @Test
    fun `a knock a second after the pattern ends is heard`() {
        assertFalse(BuzzEcho.ignores(pattern, 100.0, 100.0 + pattern.totalSeconds + 1.0))
    }

    @Test
    fun `a knock within the tail after the last pulse is ignored`() {
        assertTrue(BuzzEcho.ignores(pattern, 100.0, 100.0 + pattern.totalSeconds + 0.3))
    }

    @Test
    fun `a knock within the tail after an early pulse is ignored but one past it is heard`() {
        val spaced = AlertVibration(listOf(AlertVibration.Pulse(0.0, 0.2, 1f, 1f), AlertVibration.Pulse(2.0, 0.2, 1f, 1f)))
        assertTrue(BuzzEcho.ignores(spaced, 100.0, 100.5))
        assertFalse(BuzzEcho.ignores(spaced, 100.0, 101.0))
    }

    @Test
    fun `a reading from before the buzz began is not ignored`() {
        assertFalse(BuzzEcho.ignores(pattern, 100.0, 99.0))
    }
}
