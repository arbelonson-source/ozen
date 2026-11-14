package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun supervisor() = SoundClassifierSupervisor(RestartBudget(limit = 5, windowSeconds = 600.0, minimumSpacingSeconds = 15.0))

private fun seconds(from: Double, until: Double, by: Double): List<Double> =
    generateSequence(from) { it + by }.takeWhile { it < until }.toList()

class SoundClassifierSupervisorTest {
    @Test
    fun `a healthy classifier is never reported as unavailable`() {
        val supervisor = supervisor()
        for (second in seconds(0.0, 120.0, 0.5)) {
            assertEquals(SoundClassifierSupervisor.Step.Classify, supervisor.step(true, second))
        }
        assertTrue(supervisor.isAvailable)
        assertNull(supervisor.takeChange())
    }

    @Test
    fun `a classifier that always fails is reported unavailable, and is tried again whenever the restarts allow`() {
        val supervisor = supervisor()
        var restarts = 0
        for (second in seconds(0.0, 1_300.0, 5.0)) {
            if (supervisor.step(false, second) == SoundClassifierSupervisor.Step.Restart) {
                restarts += 1
                supervisor.restartGaveNoClassifier()
            }
        }
        assertFalse(supervisor.isAvailable)
        assertTrue(restarts > 5)
    }

    @Test
    fun `the report goes out once when it turns unavailable, not with every chunk`() {
        val supervisor = supervisor()
        val changes = mutableListOf<Boolean>()
        for (second in seconds(0.0, 300.0, 1.0)) {
            if (supervisor.step(false, second) == SoundClassifierSupervisor.Step.Restart) supervisor.restartGaveNoClassifier()
            supervisor.takeChange()?.let { changes += it }
        }
        assertEquals(listOf(false), changes)
    }

    @Test
    fun `restarts used up on a classifier that loads but fails again at once also report it unavailable`() {
        val supervisor = supervisor()
        for (second in seconds(0.0, 61.0, 15.0)) {
            assertEquals(SoundClassifierSupervisor.Step.Restart, supervisor.step(false, second))
            assertEquals(SoundClassifierSupervisor.Step.Classify, supervisor.step(true, second + 1))
        }
        assertNull(supervisor.takeChange())
        assertEquals(SoundClassifierSupervisor.Step.Skip, supervisor.step(false, 101.0))
        assertEquals(SoundClassifierSupervisor.Step.Skip, supervisor.step(false, 102.0))
        assertFalse(supervisor.isAvailable)
        assertEquals(false, supervisor.takeChange())
    }

    @Test
    fun `it comes back once a classifier has worked for a while, not on the first chunk`() {
        val supervisor = supervisor()
        supervisor.step(false, 0.0)
        supervisor.restartGaveNoClassifier()
        assertEquals(false, supervisor.takeChange())
        assertEquals(SoundClassifierSupervisor.Step.Classify, supervisor.step(true, 20.0))
        assertFalse(supervisor.isAvailable)
        supervisor.step(true, 39.0)
        assertFalse(supervisor.isAvailable)
        supervisor.step(true, 41.0)
        assertTrue(supervisor.isAvailable)
        assertEquals(true, supervisor.takeChange())
    }

    @Test
    fun `a classifier that fails again before it has proven itself keeps it unavailable`() {
        val supervisor = supervisor()
        supervisor.step(false, 0.0)
        supervisor.restartGaveNoClassifier()
        for (second in seconds(100.0, 400.0, 15.0)) {
            assertEquals(SoundClassifierSupervisor.Step.Classify, supervisor.step(true, second))
            assertTrue(supervisor.step(false, second + 10) != SoundClassifierSupervisor.Step.Classify)
        }
        assertFalse(supervisor.isAvailable)
    }
}
