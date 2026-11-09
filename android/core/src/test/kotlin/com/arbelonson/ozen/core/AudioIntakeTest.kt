package com.arbelonson.ozen.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioIntakeTest {
    private fun hum(i: Int) = 0.0003f * sin(2 * PI * 50 * i / 16_000).toFloat()

    private fun voice(i: Int) = 0.05f * sin(2 * PI * 220 * i / 16_000).toFloat()

    private val signal = FloatArray(24_000) { if (it in 1_600 until 5_000 || it in 18_000 until 21_000) voice(it) else hum(it) }

    private fun heard(size: Int): Pair<Int?, Int?> {
        val intake = AudioIntake(null)
        for (start in signal.indices step size) intake.append(signal.copyOfRange(start, minOf(start + size, signal.size)))
        val status = intake.status()
        return status.firstSpeechStart to status.lastSpeechEnd
    }

    @Test
    fun `the voice detector hears the same speech whatever size of pieces the microphone hands over`() {
        val expected = heard(688)
        for (size in listOf(1_600, 441, signal.size)) {
            assertEquals(expected, heard(size), "handed over $size samples at a time")
        }
    }

    @Test
    fun `the voice detector reads 688 samples at a time, the 43 ms its rates were tuned on`() {
        assertEquals(2 * 688 to 31 * 688, heard(1_600))
    }

    @Test
    fun `a reading that began in audio dropped since places speech from the start of what is kept`() {
        val intake = AudioIntake(null)
        intake.append(FloatArray(1_000) { hum(it) })
        intake.drop(1_000)
        intake.append(FloatArray(688) { voice(it) })
        val status = intake.status()
        assertEquals(Triple(688, 0, 376), Triple(status.count, status.firstSpeechStart, status.lastSpeechEnd))
    }

    private val chunk = 2 * EnergyVoiceDetector.BLOCK_SAMPLES

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
