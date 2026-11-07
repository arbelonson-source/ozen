package com.arbelonson.ozen.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundAlertGateTest {
    private fun SoundAlertGate.heard(chunk: FloatArray, alertsOn: Boolean) = pass(chunk, alertsOn).map { it.toList() }

    @Test
    fun `with alerts on every chunk goes through as it is, with no silence added`() {
        val gate = SoundAlertGate(windowSamples = 5)
        assertEquals(listOf(listOf(0.1f, 0.2f)), gate.heard(floatArrayOf(0.1f, 0.2f), alertsOn = true))
        assertEquals(listOf(listOf(0.3f)), gate.heard(floatArrayOf(0.3f), alertsOn = true))
    }

    @Test
    fun `with alerts off nothing goes through, and the first chunk after they come back on follows a window of silence`() {
        val gate = SoundAlertGate(windowSamples = 5)
        assertEquals(listOf(listOf(0.1f)), gate.heard(floatArrayOf(0.1f), alertsOn = true))
        assertEquals(emptyList(), gate.heard(floatArrayOf(0.2f), alertsOn = false))
        assertEquals(emptyList(), gate.heard(floatArrayOf(0.2f), alertsOn = false))
        assertEquals(listOf(List(5) { 0f }, listOf(0.3f)), gate.heard(floatArrayOf(0.3f), alertsOn = true))
        assertEquals(listOf(listOf(0.4f)), gate.heard(floatArrayOf(0.4f), alertsOn = true))
    }

    @Test
    fun `alerts that start off give a window of silence before the first sound too`() {
        val gate = SoundAlertGate(windowSamples = 3)
        assertEquals(emptyList(), gate.heard(floatArrayOf(0.2f), alertsOn = false))
        assertEquals(listOf(List(3) { 0f }, listOf(0.3f)), gate.heard(floatArrayOf(0.3f), alertsOn = true))
    }

    @Test
    fun `no classifier window joins sound from before the switch went off to sound from after it came back on`() {
        val gate = SoundAlertGate(windowSamples = YamnetSounds.SAMPLES_PER_WINDOW)
        val windows = SoundWindows()
        val heard = mutableListOf<FloatArray>()
        fun feed(value: Float, alertsOn: Boolean) {
            gate.pass(FloatArray(1_600) { value }, alertsOn).forEach { heard += windows.add(it) }
        }
        repeat(14) { feed(0.5f, alertsOn = true) }
        repeat(20) { feed(0.7f, alertsOn = false) }
        repeat(30) { feed(0.9f, alertsOn = true) }

        assertTrue(heard.any { window -> window.all { it == 0.9f } })
        for (window in heard) {
            assertTrue(window.none { it == 0.7f })
            assertTrue(window.none { it == 0.5f } || window.none { it == 0.9f }, "a window holds sound from both sides of the gap")
        }
    }

    @Test
    fun `the classifier's window is YAMNet's unless the detector says otherwise`() {
        val detector = object : SoundEventDetecting {
            override fun observations(audio: Flow<FloatArray>) = emptyFlow<SoundObservation>()
        }
        assertEquals(YamnetSounds.SAMPLES_PER_WINDOW, detector.windowSamples)
    }
}
