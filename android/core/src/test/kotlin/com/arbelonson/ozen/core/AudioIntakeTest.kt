package com.arbelonson.ozen.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
