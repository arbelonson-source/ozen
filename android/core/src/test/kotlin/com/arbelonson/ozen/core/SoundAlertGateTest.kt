package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundAlertGateTest {
    private fun SoundAlertGate.heard(chunk: FloatArray, alertsOn: Boolean) = pass(chunk, alertsOn).map { it.toList() }

    @Test
    fun `with alerts on every chunk goes through as it is, with nothing added`() {
        val gate = SoundAlertGate()
        assertEquals(listOf(listOf(0.1f, 0.2f)), gate.heard(floatArrayOf(0.1f, 0.2f), alertsOn = true))
        assertEquals(listOf(listOf(0.3f)), gate.heard(floatArrayOf(0.3f), alertsOn = true))
    }

    @Test
    fun `with alerts off nothing goes through, and the first chunk after they come back on follows an empty one`() {
        val gate = SoundAlertGate()
        assertEquals(listOf(listOf(0.1f)), gate.heard(floatArrayOf(0.1f), alertsOn = true))
        assertEquals(emptyList(), gate.heard(floatArrayOf(0.2f), alertsOn = false))
        assertEquals(emptyList(), gate.heard(floatArrayOf(0.2f), alertsOn = false))
        assertEquals(listOf(emptyList(), listOf(0.3f)), gate.heard(floatArrayOf(0.3f), alertsOn = true))
        assertEquals(listOf(listOf(0.4f)), gate.heard(floatArrayOf(0.4f), alertsOn = true))
    }

    @Test
    fun `alerts that start off mark the gap before the first sound too`() {
        val gate = SoundAlertGate()
        assertEquals(emptyList(), gate.heard(floatArrayOf(0.2f), alertsOn = false))
        assertEquals(listOf(emptyList(), listOf(0.3f)), gate.heard(floatArrayOf(0.3f), alertsOn = true))
    }

    @Test
    fun `after the gap the classifier hears only sound from after the switch came back on`() {
        val gate = SoundAlertGate()
        val windows = SoundWindows()
        val before = mutableListOf<FloatArray>()
        val after = mutableListOf<FloatArray>()
        fun feed(value: Float, alertsOn: Boolean, into: MutableList<FloatArray>) {
            gate.pass(FloatArray(1_600) { value }, alertsOn).forEach { into += windows.add(it) }
        }
        repeat(14) { feed(0.5f, alertsOn = true, into = before) }
        repeat(20) { feed(0.7f, alertsOn = false, into = before) }
        repeat(30) { feed(0.9f, alertsOn = true, into = after) }

        assertTrue(before.isNotEmpty())
        assertTrue(after.isNotEmpty())
        for (window in after) assertTrue(window.all { it == 0.9f }, "a window after the gap holds sound from before it")
    }
}
