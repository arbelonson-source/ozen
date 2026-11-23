package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaitingPaceTest {
    private fun WaitingPace.take(count: Int) = List(count) { next() }

    @Test
    fun `the home computer is asked every 15 s for two minutes, then less and less often, and at least every five minutes`() {
        val waits = WaitingPace(15.0).take(16).map { it.seconds }
        assertEquals(List(8) { 15.0 } + listOf(30.0, 60.0, 120.0, 240.0) + List(4) { 300.0 }, waits)
    }

    @Test
    fun `the cloud is asked every 30 s for two minutes, then less and less often, and at least every five minutes`() {
        val waits = WaitingPace(30.0).take(10).map { it.seconds }
        assertEquals(List(4) { 30.0 } + listOf(60.0, 120.0, 240.0) + List(3) { 300.0 }, waits)
    }

    @Test
    fun `the processor is kept up only through the waits that end within the first ten minutes, and the first wait past them says so`() {
        for (first in listOf(15.0, 30.0)) {
            val steps = WaitingPace(first).take(30)
            val keptUp = steps.takeWhile { it.keptUp }
            assertTrue(keptUp.sumOf { it.seconds } in 480.0..600.0, "$first: $steps")
            assertTrue(steps.drop(keptUp.size).none { it.keptUp }, "$first: $steps")
            assertEquals(listOf(keptUp.size), steps.indices.filter { steps[it].letsGo }, "$first: $steps")
        }
    }

    @Test
    fun `starting over brings back the quick pace, but not the ten minutes kept up`() {
        val pace = WaitingPace(15.0)
        assertFalse(pace.take(14).last().keptUp)
        pace.startOver()
        val again = pace.take(9)
        assertEquals(List(8) { 15.0 } + 30.0, again.map { it.seconds })
        assertTrue(again.none { it.keptUp || it.letsGo }, "$again")
    }

    @Test
    fun `starting over within the first ten minutes keeps the processor up through what is left of them`() {
        val pace = WaitingPace(15.0)
        val before = pace.take(10)
        pace.startOver()
        val after = pace.take(30)
        val keptUp = (before + after).filter { it.keptUp }
        assertTrue(keptUp.sumOf { it.seconds } in 480.0..600.0, "$before $after")
        assertTrue(after.takeLast(10).none { it.keptUp }, "$after")
        assertEquals(15.0, after.first().seconds)
    }

    @Test
    fun `a first wait of ten minutes is not cut down, and only that one keeps the processor up`() {
        val steps = WaitingPace(600.0).take(5)
        assertTrue(steps.all { it.seconds == 600.0 }, "$steps")
        assertEquals(listOf(true, false, false, false, false), steps.map { it.keptUp })
    }
}
