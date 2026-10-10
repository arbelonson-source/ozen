package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.pow

class EnergyVoiceDetectorTest {
    private fun tone(amplitude: Float, count: Int = 1_024): FloatArray =
        FloatArray(count) { amplitude * sin(it.toFloat() * 0.3f) }

    @Test
    fun `silence is not speech - conversation as quiet as measurement mode delivers it is`() {
        val detector = EnergyVoiceDetector()
        val silence = detector.isSpeech(FloatArray(1_024))
        // RMS about -65 dBFS: a quiet room.
        val room = detector.isSpeech(tone(amplitude = 0.0008f))
        // RMS about -55 dBFS: someone talking across the table. Under the
        // old -44 dBFS threshold this never counted.
        val acrossTheTable = detector.isSpeech(tone(amplitude = 0.0025f))
        val speaking = detector.isSpeech(tone(amplitude = 0.05f))
        assertFalse(silence)
        assertFalse(room)
        assertTrue(acrossTheTable)
        assertTrue(speaking)
    }

    @Test
    fun `a long stretch of speech, with the short gaps speech has, stays classified as speech`() {
        val detector = EnergyVoiceDetector()
        var words = 0
        var wordsHeard = 0
        // 1024-sample chunks are 64 ms: words of about 320 ms between gaps
        // of about 130 ms, for over three minutes.
        for (index in 0 until 3_000) {
            if (index % 7 < 5) {
                words += 1
                if (detector.isSpeech(tone(amplitude = 0.01f))) wordsHeard += 1
            } else {
                detector.isSpeech(tone(amplitude = 0.0005f))
            }
        }
        assertEquals(words, wordsHeard)
        assertTrue(detector.noiseFloor < 0.001f)
    }

    @Test
    fun `a steady hum louder than the threshold stops counting as speech within seconds, with no quieter moments`() {
        val detector = EnergyVoiceDetector()
        // RMS about -44 dBFS: a fridge or an air conditioner near the phone.
        val hum = tone(amplitude = 0.009f)
        val humAtFirst = detector.isSpeech(hum)
        assertTrue(humAtFirst)
        // Ten seconds of nothing but the hum.
        repeat(156) { detector.isSpeech(hum) }
        val humLater = detector.isSpeech(hum)
        val speechOverIt = detector.isSpeech(tone(amplitude = 0.05f))
        assertFalse(humLater)
        assertTrue(speechOverIt)
    }

    @Test
    fun `without the recent-minimum rule a steady hum would count as speech for good`() {
        val detector = EnergyVoiceDetector(recentWindowSamples = 0)
        val hum = tone(amplitude = 0.009f)
        repeat(156) { detector.isSpeech(hum) }
        val humLater = detector.isSpeech(hum)
        assertTrue(humLater)
    }

    @Test
    fun `steady noise lowers the margin speech needs to 6 dB - noise that swings keeps it at 8 dB`() {
        val steady = EnergyVoiceDetector()
        assertTrue(abs(steady.currentNoiseFloorRatio - 2.5f) < 0.01f)
        val hum = tone(amplitude = 0.002f, count = 1_600)
        repeat(600) { steady.isSpeech(hum) }
        assertTrue(steady.noiseSwingDecibels < 0.5f)
        assertTrue(steady.currentNoiseFloorRatio < 2.15f)
        assertTrue(abs(steady.currentNoiseFloorRatio - 2.0f) < 0.01f)
        // Six decibels above the hum: speech with the lower margin.
        val justAbove = steady.isSpeech(tone(amplitude = 0.0042f, count = 1_600))
        assertTrue(justAbove)

        val swinging = EnergyVoiceDetector()
        for (i in 0 until 600) {
            val decibels = ((i * 7) % 13).toFloat() - 6
            swinging.isSpeech(tone(amplitude = 0.002f * 10f.pow(decibels / 20), count = 1_600))
        }
        assertTrue(swinging.noiseSwingDecibels > 2)
        assertTrue(abs(swinging.currentNoiseFloorRatio - 2.5f) < 0.01f)

        // A decibel more margin for each decibel of swing: noise whose
        // quieter moments are a tenth below the rest needs 2.0 x 1.1.
        val slightly = EnergyVoiceDetector()
        for (i in 0 until 600) {
            slightly.isSpeech(tone(amplitude = if (i % 10 == 0) 0.002f else 0.0022f, count = 1_600))
        }
        assertTrue(abs(slightly.currentNoiseFloorRatio - 2.2f) < 0.002f)
    }

    @Test
    fun `the line for Whisper hears a voice 5 dB over a steady hum that the default line misses`() {
        val hum = tone(amplitude = 0.002f, count = 1_600)
        val quietVoice = tone(amplitude = 0.002f * 10f.pow(5f / 20), count = 1_600)
        val standard = EnergyVoiceDetector()
        val forWhisper = EnergyVoiceDetector.forWhisperLines()
        repeat(600) {
            standard.isSpeech(hum)
            forWhisper.isSpeech(hum)
        }
        val standardHears = standard.isSpeech(quietVoice)
        val whisperHears = forWhisper.isSpeech(quietVoice)
        val whisperHearsHum = forWhisper.isSpeech(hum)
        assertFalse(standardHears)
        assertTrue(whisperHears)
        assertFalse(whisperHearsHum)
    }

    @Test
    fun `a window only a few chunks long can't tell how the noise swings, so the margin stays cautious`() {
        val detector = EnergyVoiceDetector(recentWindowSamples = 3_200)
        for (i in 0 until 600) {
            detector.isSpeech(tone(amplitude = if (i % 2 == 0) 0.001f else 0.004f, count = 1_600))
        }
        assertTrue(abs(detector.currentNoiseFloorRatio - 2.5f) < 0.01f)
    }

    @Test
    fun `five chunks in the window are enough to tell how a hum swings - four are not`() {
        val hum = tone(amplitude = 0.002f, count = 1_600)
        val five = EnergyVoiceDetector(recentWindowSamples = 5 * 1_600)
        val four = EnergyVoiceDetector(recentWindowSamples = 4 * 1_600)
        repeat(600) {
            five.isSpeech(hum)
            four.isSpeech(hum)
        }
        assertTrue(abs(five.currentNoiseFloorRatio - 2.0f) < 0.01f)
        assertTrue(abs(four.currentNoiseFloorRatio - 2.5f) < 0.01f)
    }

    @Test
    fun `a glitched chunk, NaN or infinite, is not speech and doesn't stop the floor from following a hum`() {
        val detector = EnergyVoiceDetector()
        val hum = tone(amplitude = 0.009f)
        // Wholly corrupted, not just a single bad sample among mostly-good
        // ones (a single stray sample is now tolerated; see
        // a single corrupted sample doesn't discard an otherwise loud, real chunk).
        val notANumber = FloatArray(hum.size) { Float.NaN }
        val infinite = FloatArray(hum.size) { Float.POSITIVE_INFINITY }
        val notANumberAtFirst = detector.isSpeech(notANumber)
        for (index in 0 until 156) {
            detector.isSpeech(if (index == 80) infinite else hum)
        }
        val infiniteLater = detector.isSpeech(infinite)
        val humLater = detector.isSpeech(hum)
        val speechOverIt = detector.isSpeech(tone(amplitude = 0.05f))
        assertTrue(!notANumberAtFirst && !infiniteLater)
        assertTrue(detector.noiseFloor.isFinite() && detector.noiseSwingDecibels.isFinite())
        assertFalse(humLater)
        assertTrue(speechOverIt)
    }

    @Test
    fun `a single corrupted sample doesn't discard an otherwise loud, real chunk`() {
        val detector = EnergyVoiceDetector()
        val speech = tone(amplitude = 0.05f)
        speech[10] = Float.NaN
        val stillSpeech = detector.isSpeech(speech)
        assertTrue(stillSpeech)

        val mostlyGlitched = FloatArray(1_024)
        for (i in 0 until 600) mostlyGlitched[i] = Float.NaN
        for (i in 600 until 1_024) mostlyGlitched[i] = 0.05f * sin(i.toFloat() * 0.3f)
        val notSpeech = detector.isSpeech(mostlyGlitched)
        assertFalse(notSpeech)
    }

    @Test
    fun `the floor is capped so a loud fan can't disable detection`() {
        val detector = EnergyVoiceDetector()
        repeat(1_000) { detector.isSpeech(tone(amplitude = 0.004f)) }
        assertTrue(detector.noiseFloor <= detector.maximumNoiseFloor)
        val loud = detector.isSpeech(tone(amplitude = 0.2f))
        assertTrue(loud)
    }

    @Test
    fun `the floor falls quickly when the room gets quieter`() {
        val detector = EnergyVoiceDetector(initialNoiseFloor = 0.02f)
        repeat(20) { detector.isSpeech(tone(amplitude = 0.0002f)) }
        assertTrue(detector.noiseFloor < 0.001f)
    }

    @Test
    fun `the floor creeps up to a steady sound under the line, and never past it`() {
        val detector = EnergyVoiceDetector()
        val sound = tone(amplitude = 0.001f)
        var sumOfSquares = 0f
        for (sample in sound) sumOfSquares += sample * sample
        val level = kotlin.math.sqrt(sumOfSquares / sound.size.toFloat())
        var highest = 0f
        var heardAsSpeech = 0
        repeat(500) {
            if (detector.isSpeech(sound)) heardAsSpeech += 1
            highest = maxOf(highest, detector.noiseFloor)
        }
        assertEquals(0, heardAsSpeech)
        assertTrue(highest <= level * 1.0001f)
        assertTrue(highest > level * 0.9f)
    }

    @Test
    fun `a dropout of digital silence, empty or zeroed buffers, says nothing about the room - a hum stays a hum after it`() {
        val detector = EnergyVoiceDetector()
        val hum = tone(amplitude = 0.007f)
        repeat(235) { detector.isSpeech(hum) }
        val settled = detector.isSpeech(hum)
        assertFalse(settled)
        val floor = detector.noiseFloor
        // A Bluetooth dropout, an empty buffer, and a glitch passed on as
        // silence by AudioFanOut.
        repeat(8) { detector.isSpeech(FloatArray(1_024)) }
        repeat(30) { detector.isSpeech(FloatArray(0)) }
        repeat(3) { detector.isSpeech(AudioFanOut.withoutGlitches(FloatArray(1_024) { Float.NaN })) }
        assertEquals(floor, detector.noiseFloor)
        var humAsSpeech = 0
        repeat(50) { if (detector.isSpeech(hum)) humAsSpeech += 1 }
        assertEquals(0, humAsSpeech)
        val voice = detector.isSpeech(tone(amplitude = 0.05f))
        assertTrue(voice)
    }

    @Test
    fun `rms and meter level behave at the edges`() {
        assertEquals(0f, EnergyVoiceDetector.rms(FloatArray(0)))
        assertTrue(abs(EnergyVoiceDetector.rms(floatArrayOf(1f, -1f, 1f, -1f)) - 1) < 0.0001f)
        assertEquals(0f, EnergyVoiceDetector.meterLevel(forRMS = 0f))
        assertEquals(1f, EnergyVoiceDetector.meterLevel(forRMS = 1f))
        val mid = EnergyVoiceDetector.meterLevel(forRMS = 0.0316f)
        assertTrue(mid > 0.52f && mid < 0.62f)
        // -30 dBFS is 40 of the meter's 70 dB.
        assertTrue(abs(mid - 4.0f / 7) < 0.005f)
        // Conversation at -55 dBFS moves the meter; a -80 dBFS room doesn't.
        assertTrue(EnergyVoiceDetector.meterLevel(forRMS = 0.0018f) > 0.15f)
        assertEquals(0f, EnergyVoiceDetector.meterLevel(forRMS = 0.0001f))
    }
}
