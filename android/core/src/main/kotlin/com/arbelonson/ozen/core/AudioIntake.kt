package com.arbelonson.ozen.core

import kotlin.math.max
import kotlin.math.min

internal class AudioIntake(voiceScorer: ((FloatArray) -> Float?)?) {
    class Status(val count: Int, val lastSpeechEnd: Int?, val finished: Boolean)

    private val lock = Any()
    private var samples = FloatArray(16_000 * 30)
    private var size = 0
    private var lastSpeechEnd: Int? = null
    private var finished = false
    private val detector = EnergyVoiceDetector.forWhisperLines()
    private val evidence: VoiceEvidence? = voiceScorer?.let { VoiceEvidence(it) }

    fun append(chunk: FloatArray) {
        synchronized(lock) {
            if (size + chunk.size > samples.size) {
                samples = samples.copyOf(max(samples.size * 2, size + chunk.size))
            }
            chunk.copyInto(samples, size)
            size += chunk.size
            if (detector.isSpeech(chunk)) {
                lastSpeechEnd = size
            }
            evidence?.append(chunk)
        }
    }

    fun hasVoice(upTo: Int): Boolean? = synchronized(lock) { evidence?.hasVoice(upTo) }

    fun voicedChunks(upTo: Int): Int? = synchronized(lock) { evidence?.voicedChunks(upTo) }

    fun markFinished() {
        synchronized(lock) { finished = true }
    }

    fun status(): Status = synchronized(lock) { Status(size, lastSpeechEnd, finished) }

    fun copySamples(upTo: Int): FloatArray = synchronized(lock) {
        samples.copyOfRange(0, min(max(upTo, 0), size))
    }

    fun drop(prefix: Int) {
        synchronized(lock) {
            val dropped = min(prefix, size)
            samples.copyInto(samples, 0, dropped, size)
            size -= dropped
            evidence?.drop(dropped)
            lastSpeechEnd?.let { end -> lastSpeechEnd = if (end > dropped) end - dropped else null }
        }
    }
}
