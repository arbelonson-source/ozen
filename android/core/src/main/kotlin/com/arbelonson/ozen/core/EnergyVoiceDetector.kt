package com.arbelonson.ozen.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A small, adaptive energy-based voice activity detector. Both engines
 * need to know "is anyone talking right now": Whisper so it never runs
 * inference on pure silence (wasted battery and, worse, hallucinated
 * text), Apple Speech so it can end a request at a natural pause and
 * start a new utterance. Not a neural VAD; deliberately simple, tuned to
 * be conservative about *missing* speech rather than about false alarms,
 * since a false alarm costs a wasted inference and a miss costs words.
 */
class EnergyVoiceDetector(
    /**
     * Below this RMS (-60 dBFS) nothing counts as speech, whatever the
     * noise floor says. Guards against a dead-quiet room where the floor
     * would otherwise drop to ~0 and make breathing count as speech.
     *
     * It was -44 dBFS. The session runs in measurement mode, which hands
     * over the microphone's signal without the automatic gain that makes
     * calls and voice memos loud, and a phone microphone puts ordinary
     * conversation a metre or two away somewhere around -45 to -60 dBFS.
     * In a simulation over 40 LibriSpeech recordings run through this
     * detector and Whisper's pause logic, speech at -50 dBFS reached
     * Whisper 20-37% of the time and at -55 dBFS not at all; with -60 dBFS
     * both reached it 96-97% of the time. Whisper itself doesn't mind the
     * level: its word error rate was the same at -26 and -56 dBFS.
     */
    var absoluteThreshold: Float = 0.001f,
    /**
     * Speech must exceed the tracked noise floor by this factor (8 dB) in
     * a room whose noise swings, down to [steadyNoiseFloorRatio] (6 dB) in
     * one whose noise holds still (see [noiseSwingDecibels]).
     */
    var noiseFloorRatio: Float = 2.5f,
    /** How quickly the floor follows a *quieter* signal (per chunk). */
    var floorFallRate: Float = 0.3f,
    /**
     * How quickly the floor rises toward a louder non-speech signal
     * (per chunk). Kept far slower than the fall rate so sustained
     * speech can't drag the floor up until it counts as noise.
     */
    var floorRiseRate: Float = 0.02f,
    /**
     * The floor never rises above this, so a loud steady hum (a fan next
     * to the mic) can't switch detection off entirely.
     */
    var maximumNoiseFloor: Float = 0.02f,
    initialNoiseFloor: Float = 0.0004f,
    /**
     * How much audio (in samples) the quietest recent moment is taken
     * from.
     *
     * Only quiet chunks feed the floor, so a steady sound louder than the
     * threshold (a fridge, an air conditioner) would count as speech for
     * ever, and with the lower threshold that's an ordinary room: Whisper
     * running on it all evening, in the same simulation 98-100% of the
     * time. Speech always has gaps within a few seconds, a hum doesn't: so
     * when even the quietest chunk of the last three seconds is above the
     * floor, the floor rises toward it, and the hum stops counting while
     * people talking over it still do.
     *
     * Someone talking on without stopping keeps those gaps too: with the
     * recordings run together 0.1-0.6 s apart for four minutes, in quiet
     * and in noisy rooms, speech reached Whisper as often with this as
     * without it (89-94%), and no less in the last minute than the first.
     * What it does cost is speech barely louder than a hum: 3 dB above a
     * -55 dBFS hum, a quarter of it got through; 10 dB above, nearly all.
     */
    var recentWindowSamples: Int = 48_000,
    /** How quickly the floor rises toward that quietest recent level (per chunk). */
    var recentMinimumRiseRate: Float = 0.05f,
    /**
     * The factor for steady noise: a fan, an air conditioner, a fridge's
     * hum. Noise that barely moves can't cross a lower line by chance.
     *
     * In the simulation, 6 dB everywhere let speech 6 dB above a steady
     * -56 dBFS fan through 89% of the time instead of 64%, but in a room of
     * rumbling, swinging noise 26% of the silence between sentences went to
     * Whisper instead of 4%. Following the swing kept that room at 8 dB (and
     * 5% of its silence) while the fan got 6 dB: 80-82% of speech reached
     * Whisper there, and over a steady hum 83% instead of 63%. With
     * 43 ms chunks instead of 100 ms the gains held (fan 78% to 87%, hum
     * 77% to 88%) and no more silence went through.
     */
    var steadyNoiseFloorRatio: Float = 2.0f,
    /** How quickly [noiseSwingDecibels] follows the last few seconds (per chunk). */
    var noiseSwingRate: Float = 0.02f,
) {
    var noiseFloor: Float = initialNoiseFloor
        private set

    var lastLevel: Float = 0f
        private set

    /**
     * How far the noise swings: over the last [recentWindowSamples], the
     * gap in dB between the quietest chunk and the one a fifth of the way
     * up. A fan keeps it well under a decibel; a rumbling room, or someone
     * talking through most of the window, several. Starts high, so a new
     * session begins at the cautious 8 dB.
     */
    var noiseSwingDecibels: Float = 2f
        private set

    private class Recent(val level: Float, val samples: Int)

    private val recentLevels = ArrayDeque<Recent>()
    private var recentSamples = 0

    val threshold: Float
        get() = max(absoluteThreshold, noiseFloor * currentNoiseFloorRatio)

    /** Between [steadyNoiseFloorRatio] and [noiseFloorRatio], a decibel higher for each decibel the noise swings. */
    val currentNoiseFloorRatio: Float
        get() {
            val steady = 20 * log10(steadyNoiseFloorRatio)
            val swinging = 20 * log10(max(noiseFloorRatio, steadyNoiseFloorRatio))
            val decibels = min(max(steady + noiseSwingDecibels, steady), swinging)
            return 10f.pow(decibels / 20)
        }

    /**
     * Classifies one chunk and updates the noise floor. Chunks that are
     * *not* speech feed the floor, which is what keeps a long monologue
     * from being reclassified as background noise; a sound with no quiet
     * moment in the last few seconds lifts it too (see [recentWindowSamples]).
     */
    fun isSpeech(samples: FloatArray): Boolean {
        val level = rms(samples)
        // A glitched buffer can carry a NaN or infinite sample. Its level
        // in the floor or the recent window would stay there for good,
        // leaving only the absolute threshold: a hum would count as speech
        // all evening.
        if (!level.isFinite()) {
            lastLevel = 0f
            return false
        }
        lastLevel = level
        // Digital silence, a buffer of zeros or none at all, is a dropout
        // (a Bluetooth hiccup, a glitch AudioFanOut passed on as silence),
        // not the room going quiet. Taken as the room, it pulled the floor
        // down and held the recent window at zero, and the hum that came
        // back counted as speech for seconds.
        if (level < DIGITAL_SILENCE) return false
        followQuietestRecentLevel(level, samples.size)
        val speech = level > threshold
        if (!speech) {
            if (level < noiseFloor) {
                noiseFloor += (level - noiseFloor) * floorFallRate
            } else {
                noiseFloor += (level - noiseFloor) * floorRiseRate
            }
            noiseFloor = min(noiseFloor, maximumNoiseFloor)
        }
        return speech
    }

    private fun followQuietestRecentLevel(level: Float, samples: Int) {
        if (recentWindowSamples <= 0 || samples <= 0) return
        recentLevels.addLast(Recent(level, samples))
        recentSamples += samples
        while (recentLevels.isNotEmpty() && recentSamples - recentLevels.first().samples >= recentWindowSamples) {
            recentSamples -= recentLevels.removeFirst().samples
        }
        // Only once the window holds a full few seconds: at the start of
        // listening the first chunk alone is the "quietest".
        if (recentSamples < recentWindowSamples) return
        val levels = recentLevels.map { it.level }.sorted()
        val quietest = levels[0]
        // A fifth of fewer than five levels is the quietest itself, which
        // would read as noise that never swings.
        if (quietest > 0 && levels.size >= 5) {
            val swing = 20 * log10(levels[levels.size / 5] / quietest)
            noiseSwingDecibels += (swing - noiseSwingDecibels) * noiseSwingRate
        }
        if (quietest <= noiseFloor) return
        noiseFloor = min(noiseFloor + (quietest - noiseFloor) * recentMinimumRiseRate, maximumNoiseFloor)
    }

    companion object {
        /**
         * Below any microphone's own hiss, and below what 16-bit audio can
         * even carry (about 1e-5): no sound arrived at all.
         */
        internal const val DIGITAL_SILENCE: Float = 1e-6f

        /**
         * The line for cutting what goes to Whisper, a fifth lower than the
         * default (6.0 dB over the noise floor, 4.1 dB when it holds still).
         * Measured on the owner's desktop with the voice check on: a speaker
         * across the room 51.3 -> 48.3% of words wrong, the same speaker
         * quieter 86.5 -> 77.5%, conversation and lectures unchanged (8.9%,
         * 13.3 -> 13.2%), and household noise 1 invented line in 12 minutes
         * where it was none. Lower still (x0.65) cost conversation (9.3%).
         * What the lower line lets through is mostly noise the voice check
         * (VoiceEvidence) then keeps from Whisper.
         */
        fun forWhisperLines(): EnergyVoiceDetector =
            EnergyVoiceDetector(noiseFloorRatio = 2.0f, steadyNoiseFloorRatio = 1.6f)

        fun rms(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0f
            var validCount = 0
            for (sample in samples) {
                if (sample.isFinite()) {
                    sum += sample * sample
                    validCount += 1
                }
            }
            // A single corrupted sample (a known Core Audio glitch class)
            // shouldn't discard an otherwise-real chunk of speech; but a
            // majority-corrupted buffer is still reported as non-finite so
            // isSpeech's existing guard catches it as before.
            if (validCount * 2 < samples.size) return Float.NaN
            return sqrt(sum / validCount.toFloat())
        }

        /**
         * Maps an RMS level to 0...1 for a meter, on a decibel scale from
         * -70 dBFS (silent) to 0 dBFS (clipping), which is how loudness is
         * actually perceived. The bottom was -50 dBFS, where the meter sat
         * still through conversation arriving at -55 dBFS and looked like a
         * dead microphone.
         */
        fun meterLevel(forRMS: Float): Float {
            if (!(forRMS > 0)) return 0f
            val decibels = 20 * log10(forRMS)
            return min(max((decibels + 70) / 70, 0f), 1f)
        }
    }
}
