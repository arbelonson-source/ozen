package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AlertVibrationTest {
    private val kinds: List<AlertVibration> =
        SoundEvent.Importance.entries.map { AlertVibration.pattern(it) } + listOf(AlertVibration.keyword, AlertVibration.speechResumed)

    @Test
    fun `a siren, the door, anything else, her name and someone starting to talk each feel different`() {
        val distinct = listOf(
            AlertVibration.pattern(SoundEvent.Importance.Critical),
            AlertVibration.pattern(SoundEvent.Importance.High),
            AlertVibration.pattern(SoundEvent.Importance.Medium),
            AlertVibration.keyword,
            AlertVibration.speechResumed,
        )
        for ((index, pattern) in distinct.withIndex()) {
            for (other in distinct.drop(index + 1)) {
                assertNotEquals(pattern, other)
                val differsInCount = pattern.pulses.size != other.pulses.size
                val differsInKind = pattern.pulses.any { it.duration > 0 } != other.pulses.any { it.duration > 0 }
                assertTrue(differsInCount || differsInKind)
            }
        }
    }

    @Test
    fun `a more important sound never vibrates for less time or with fewer pulses`() {
        val ordered = SoundEvent.Importance.entries.sorted().map { AlertVibration.pattern(it) }
        for ((lower, higher) in ordered.zipWithNext()) {
            assertTrue(higher.totalSeconds >= lower.totalSeconds)
            assertTrue(higher.pulses.size >= lower.pulses.size)
        }
    }

    @Test
    fun `someone starting to talk is gentler than any alert`() {
        val strongest = AlertVibration.speechResumed.pulses.maxOf { it.intensity }
        val alerts = SoundEvent.Importance.entries.map { AlertVibration.pattern(it) } + listOf(AlertVibration.keyword)
        for (alert in alerts) {
            assertTrue(strongest < alert.pulses.minOf { it.intensity })
        }
    }

    @Test
    fun `a safety sound buzzes for a few seconds, then stops`() {
        val critical = AlertVibration.pattern(SoundEvent.Importance.Critical)
        assertTrue(critical.totalSeconds >= 2)
        assertTrue(critical.totalSeconds <= 4)
        assertTrue(critical.pulses.all { it.duration >= 0.3 })
    }

    @Test
    fun `a pattern lasts until its last pulse ends`() {
        val pattern = AlertVibration(
            listOf(
                AlertVibration.Pulse(start = 0.0, duration = 0.2, intensity = 1f, sharpness = 0.5f),
                AlertVibration.Pulse(start = 1.0, duration = 0.5, intensity = 1f, sharpness = 0.5f),
            ),
        )
        assertEquals(listOf(0.2, 1.5), pattern.pulses.map { it.end })
        assertEquals(1.5, pattern.totalSeconds)
    }

    @Test
    fun `every pattern starts buzzing the moment it is played, with the banner and the flash`() {
        for (pattern in kinds) {
            assertEquals(0.0, pattern.pulses.firstOrNull()?.start)
        }
    }

    @Test
    fun `every pattern has pulses in order that don't overlap, at strengths the hardware accepts`() {
        for (pattern in kinds) {
            assertTrue(pattern.pulses.isNotEmpty())
            for ((earlier, later) in pattern.pulses.zipWithNext()) {
                assertTrue(later.start >= earlier.end + 0.1)
            }
            assertTrue(pattern.pulses.all { it.intensity in 0f..1f && it.sharpness in 0f..1f && it.start >= 0 })
        }
    }
}
