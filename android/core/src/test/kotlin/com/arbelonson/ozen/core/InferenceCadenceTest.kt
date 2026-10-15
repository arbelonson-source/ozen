package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InferenceCadenceTest {
    @Test
    fun `a cool phone refreshes the live text at the base rate`() {
        assertEquals(InferenceCadence.BASE_SECONDS, InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Nominal, lowPowerMode = false, lastPassSeconds = 0.2))
        assertEquals(InferenceCadence.BASE_SECONDS, InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Fair, lowPowerMode = false, lastPassSeconds = null))
    }

    @Test
    fun `the hotter the phone, the slower the live preview`() {
        val intervals = DeviceHeat.entries.map {
            InferenceCadence.secondsBetweenLivePasses(it, lowPowerMode = false, lastPassSeconds = null)
        }
        assertEquals(intervals.sorted(), intervals)
        assertTrue(intervals[DeviceHeat.Serious.rawValue] > InferenceCadence.BASE_SECONDS)
        assertTrue(intervals[DeviceHeat.Critical.rawValue] > intervals[DeviceHeat.Serious.rawValue])
    }

    @Test
    fun `Low Power Mode slows a cool phone but never speeds up a hot one`() {
        val coolSaver = InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Nominal, lowPowerMode = true, lastPassSeconds = null)
        assertTrue(coolSaver > InferenceCadence.BASE_SECONDS)
        val critical = InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Critical, lowPowerMode = false, lastPassSeconds = null)
        val criticalSaver = InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Critical, lowPowerMode = true, lastPassSeconds = null)
        assertEquals(critical, criticalSaver)
    }

    @Test
    fun `a pass that took longer than the interval doubles it, up to the rest cap`() {
        assertEquals(1.2, InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Nominal, lowPowerMode = false, lastPassSeconds = 0.6))
    }

    @Test
    fun `a slow pass never makes the next one wait more than the rest cap`() {
        assertEquals(InferenceCadence.MAX_REST_SECONDS, InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Nominal, lowPowerMode = false, lastPassSeconds = 1.7))
        assertEquals(InferenceCadence.MAX_REST_SECONDS, InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Nominal, lowPowerMode = false, lastPassSeconds = 12.0))
    }

    @Test
    fun `the rest cap does not speed up a hot phone`() {
        assertEquals(4.0, InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Critical, lowPowerMode = false, lastPassSeconds = 12.0))
    }

    @Test
    fun `a pass of half a second, shorter than a second but over half the base, still gets as long again to rest`() {
        assertEquals(1.0, InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Nominal, lowPowerMode = false, lastPassSeconds = 0.5))
    }

    @Test
    fun `a nonsense pass duration is ignored`() {
        assertEquals(InferenceCadence.BASE_SECONDS, InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Nominal, lowPowerMode = false, lastPassSeconds = Double.POSITIVE_INFINITY))
        assertEquals(InferenceCadence.BASE_SECONDS, InferenceCadence.secondsBetweenLivePasses(DeviceHeat.Nominal, lowPowerMode = false, lastPassSeconds = -3.0))
    }
}
