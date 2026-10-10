package com.arbelonson.ozen.core

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechIntakeTest {
    private val chunk = 1_600

    private fun speech(): FloatArray = FloatArray(chunk) { 0.05f * sin(it.toFloat() * 0.3f) }

    private fun silence(): FloatArray = FloatArray(chunk)

    private fun intake(pattern: String): SpeechIntake {
        val intake = SpeechIntake()
        for (mark in pattern) {
            intake.append(if (mark == 's') speech() else silence())
        }
        return intake
    }

    @Test
    fun `speech is placed from the start of its first chunk to the end of its last, silence alone places nothing`() {
        assertEquals(SpeechIntake.Status(3 * chunk, null, null, false), intake("...").status())
        assertEquals(SpeechIntake.Status(8 * chunk, 2 * chunk, 6 * chunk, false), intake("..ss.s..").status())
    }

    @Test
    fun `dropping silence ahead of speech moves the speech back by as much`() {
        val intake = intake("...ss.")
        intake.drop(2 * chunk)
        assertEquals(SpeechIntake.Status(4 * chunk, chunk, 3 * chunk, false), intake.status())
        intake.drop(chunk)
        assertEquals(0, intake.status().firstSpeechStart)
    }

    @Test
    fun `a finished line dropped up to its end leaves no speech behind, even when it ended exactly there`() {
        val exact = intake(".ss..")
        exact.drop(3 * chunk)
        assertEquals(SpeechIntake.Status(2 * chunk, null, null, false), exact.status())
        val padded = intake(".ss..")
        padded.drop(3 * chunk + 480)
        assertNull(padded.status().lastSpeechEnd)
        assertEquals(2 * chunk - 480, padded.status().count)
    }

    @Test
    fun `a cut inside a long line keeps the speech after it, starting at the next whole chunk of speech`() {
        val intake = intake("ssss.")
        intake.drop(2 * chunk + 100)
        assertEquals(SpeechIntake.Status(3 * chunk - 100, chunk - 100, 2 * chunk - 100, false), intake.status())
        intake.drop(chunk)
        assertNull(intake.status().firstSpeechStart)
        assertEquals(chunk - 100, intake.status().lastSpeechEnd)
    }

    @Test
    fun `dropping or copying past either end is clamped, and the samples copied are the ones kept`() {
        val intake = intake("s.")
        assertTrue(intake.copySamples(-5).isEmpty())
        assertEquals(2 * chunk, intake.copySamples(10 * chunk).size)
        intake.drop(-5)
        assertEquals(2 * chunk, intake.status().count)
        intake.drop(chunk)
        assertContentEquals(silence(), intake.copySamples(chunk))
        intake.drop(10 * chunk)
        assertEquals(SpeechIntake.Status(0, null, null, false), intake.status())
    }

    @Test
    fun `the end of the audio is reported once marked, and new audio still counts after it`() {
        val intake = intake("s")
        intake.markFinished()
        intake.append(silence())
        assertEquals(SpeechIntake.Status(2 * chunk, 0, chunk, true), intake.status())
    }
}
