package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorchFlashTest {
    private val critical = SoundEvent.Importance.Critical
    private val high = SoundEvent.Importance.High

    private fun length(steps: List<TorchFlash.Step>) = steps.sumOf { it.onSeconds + it.offSeconds }

    private fun onsets(steps: List<TorchFlash.Step>): List<Double> {
        var at = 0.0
        return steps.map { step -> at.also { at += step.onSeconds + step.offSeconds } }
    }

    private fun mostOnsetsInASecond(steps: List<TorchFlash.Step>): Int {
        val times = onsets(steps)
        return times.maxOf { start -> times.count { it >= start - 1e-9 && it < start + 1.0 - 1e-9 } }
    }

    @Test
    fun `an emergency blinks long and steady, the door in pairs, each for about ten seconds`() {
        val emergency = assertNotNull(TorchFlash.pattern(critical, reduceMotion = false))
        val door = assertNotNull(TorchFlash.pattern(high, reduceMotion = false))
        assertEquals(List(8) { TorchFlash.Step(0.7, 0.5) }, emergency)
        assertEquals(List(4) { listOf(TorchFlash.Step(0.2, 0.3), TorchFlash.Step(0.2, 1.8)) }.flatten(), door)
        for (steps in listOf(emergency, door)) assertTrue(length(steps) in 9.0..10.5, "${length(steps)}")
        assertTrue(emergency.minOf { it.onSeconds } > door.maxOf { it.onSeconds })
        assertTrue(door.chunked(2).all { (first, second) -> first.offSeconds < second.offSeconds / 3 })
    }

    @Test
    fun `with Remove animations on, the flashes are fewer and slower`() {
        for (importance in listOf(critical, high)) {
            val usual = assertNotNull(TorchFlash.pattern(importance, reduceMotion = false))
            val calm = assertNotNull(TorchFlash.pattern(importance, reduceMotion = true))
            assertTrue(calm.size < usual.size, "$importance")
            assertTrue(calm.minOf { it.onSeconds } > usual.maxOf { it.onSeconds }, "$importance")
            assertTrue(length(calm) <= 10.5, "$importance")
        }
        assertEquals(List(3) { TorchFlash.Step(2.0, 1.5) }, TorchFlash.pattern(critical, reduceMotion = true))
        assertEquals(List(2) { TorchFlash.Step(1.5, 1.5) }, TorchFlash.pattern(high, reduceMotion = true))
    }

    @Test
    fun `no rhythm turns the light on more than three times in any second`() {
        val all = listOf(critical, high).flatMap { importance -> listOf(false, true).map { TorchFlash.pattern(importance, it)!! } } +
            listOf(false, true).map { TorchFlash.sample(it) }
        for (steps in all) {
            assertTrue(mostOnsetsInASecond(steps) <= AlertFlash.MAXIMUM_FLASHES_PER_SECOND, "$steps")
            assertTrue(steps.all { it.onSeconds > 0 && it.offSeconds > 0 }, "$steps")
        }
    }

    @Test
    fun `everyday sounds never light the camera`() {
        assertNull(TorchFlash.pattern(SoundEvent.Importance.Medium, reduceMotion = false))
        assertNull(TorchFlash.pattern(SoundEvent.Importance.Low, reduceMotion = true))
    }

    @Test
    fun `the sample is the door's rhythm, about five seconds of it`() {
        for (reduceMotion in listOf(false, true)) {
            val sample = TorchFlash.sample(reduceMotion)
            assertTrue(length(sample) in 4.5..6.5, "$reduceMotion ${length(sample)}")
            assertEquals(TorchFlash.pattern(high, reduceMotion)!!.take(sample.size), sample)
        }
        assertEquals(4, TorchFlash.sample(reduceMotion = false).size)
    }

    private fun flashes(
        importance: SoundEvent.Importance,
        isOn: Boolean = true,
        appIsActive: Boolean = false,
        quiet: Boolean = false,
    ): Boolean {
        val hours = if (quiet) QuietHours(isEnabled = true, startHour = 0, endHour = 0) else QuietHours(isEnabled = false)
        return TorchFlash.shouldFlash(isOn, importance, appIsActive, hours, now = 1_700_000_000.0, utcOffsetSeconds = 7_200)
    }

    @Test
    fun `the light flashes only when it is on, Ozen is away and the sound matters`() {
        assertTrue(flashes(critical))
        assertTrue(flashes(high))
        assertFalse(flashes(critical, isOn = false))
        assertFalse(flashes(high, appIsActive = true))
        assertFalse(flashes(critical, appIsActive = true))
        assertFalse(flashes(SoundEvent.Importance.Medium))
        assertFalse(flashes(SoundEvent.Importance.Low))
    }

    @Test
    fun `quiet hours hold back the door but never an emergency`() {
        assertTrue(flashes(critical, quiet = true))
        assertFalse(flashes(high, quiet = true))
        val night = QuietHours(isEnabled = true, startHour = 22, endHour = 7)
        val midnightLocal = 1_700_000_000.0 - (1_700_000_000L + 7_200) % 86_400
        assertFalse(TorchFlash.shouldFlash(true, high, false, night, midnightLocal + 3_600, utcOffsetSeconds = 7_200))
        assertTrue(TorchFlash.shouldFlash(true, high, false, night, midnightLocal + 12 * 3_600, utcOffsetSeconds = 7_200))
    }
}
