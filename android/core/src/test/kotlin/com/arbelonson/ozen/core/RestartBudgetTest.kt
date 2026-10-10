package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

private fun RestartBudget.answers(times: List<Double>): List<Boolean> = times.map { spend(it) }

class RestartBudgetTest {
    @Test
    fun `allows the limit within the window, then refuses`() {
        val budget = RestartBudget(limit = 3, windowSeconds = 600.0)
        assertEquals(listOf(true, true, true, false, false), budget.answers(listOf(0.0, 10.0, 20.0, 30.0, 599.0)))
    }

    @Test
    fun `a bad patch early in the evening doesn't use up restarts for good`() {
        val budget = RestartBudget(limit = 5, windowSeconds = 600.0)
        assertEquals(
            listOf(true, true, true, true, true, false, true, true, false),
            budget.answers(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 300.0, 600.0, 601.0, 601.5)),
        )
    }

    @Test
    fun `a rough patch of a few seconds doesn't spend every restart at once`() {
        val budget = RestartBudget(limit = 5, windowSeconds = 600.0, minimumSpacingSeconds = 15.0)
        val patch = (0..40).map { it * 0.05 }
        assertEquals(1, budget.answers(patch).count { it })
        assertEquals(listOf(false, true, false, true), budget.answers(listOf(14.9, 15.0, 20.0, 30.0)))
    }

    @Test
    fun `a clock that jumped backwards doesn't block restarts`() {
        val budget = RestartBudget(limit = 2, windowSeconds = 600.0)
        assertEquals(listOf(true, true, true), budget.answers(listOf(5_000.0, 5_001.0, 100.0)))
    }

    @Test
    fun `a limit of zero never restarts`() {
        val budget = RestartBudget(limit = 0, windowSeconds = 600.0)
        assertEquals(listOf(false, false), budget.answers(listOf(0.0, 10_000.0)))
    }
}
