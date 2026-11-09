package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioIntakeTest {
    private val chunk = 1_600

    private fun intake(pattern: String, pauseChunks: Int? = 7): AudioIntake {
        val intake = AudioIntake(null, pauseChunks?.let { it * chunk })
        for (mark in pattern) {
            intake.append(FloatArray(chunk) { if (mark == 's') 0.2f else 0f })
        }
        return intake
    }

    @Test
    fun `a pause that more speech followed is remembered where the speech stopped, a shorter gap or silence before any speech is not`() {
        val status = intake(".........ss......ss.......ss").status()
        assertEquals(listOf(19 * chunk), status.pauses)
        assertEquals(28 * chunk, status.lastSpeechEnd)
        assertEquals(9 * chunk, status.firstSpeechStart)
        assertTrue(intake("ss.......ss", pauseChunks = null).status().pauses.isEmpty())
    }

    @Test
    fun `dropping a line ended at a pause forgets that pause, moves the later ones back, and starts the speech where it resumed`() {
        val intake = intake("ss.......ss.......ss")
        assertEquals(listOf(2 * chunk, 11 * chunk), intake.status().pauses)
        intake.drop(5 * chunk)
        assertEquals(listOf(6 * chunk), intake.status().pauses)
        assertEquals(4 * chunk, intake.status().firstSpeechStart)
        intake.drop(6 * chunk)
        assertTrue(intake.status().pauses.isEmpty())
        assertEquals(7 * chunk, intake.status().firstSpeechStart)
    }

    @Test
    fun `a cut inside speech keeps the speech starting where the kept audio starts`() {
        val intake = intake("ssss.......ss")
        intake.drop(2 * chunk)
        assertEquals(0, intake.status().firstSpeechStart)
        assertEquals(listOf(2 * chunk), intake.status().pauses)
    }
}
