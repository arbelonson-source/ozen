package com.arbelonson.ozen.core

class SpeechIntake(private val detector: EnergyVoiceDetector = EnergyVoiceDetector()) {
    data class Status(val count: Int, val firstSpeechStart: Int?, val lastSpeechEnd: Int?, val finished: Boolean)

    private val lock = Any()
    private var samples = FloatArray(0)
    private var size = 0
    private var speechStarts = ArrayList<Int>()
    private var lastSpeechEnd: Int? = null
    private var finished = false

    fun append(chunk: FloatArray) {
        synchronized(lock) {
            if (size + chunk.size > samples.size) samples = samples.copyOf(maxOf(size + chunk.size, samples.size * 2))
            chunk.copyInto(samples, size)
            size += chunk.size
            if (detector.isSpeech(chunk)) {
                speechStarts.add(size - chunk.size)
                lastSpeechEnd = size
            }
        }
    }

    fun markFinished() {
        synchronized(lock) { finished = true }
    }

    fun status(): Status = synchronized(lock) {
        Status(size, speechStarts.firstOrNull(), lastSpeechEnd, finished)
    }

    fun copySamples(upTo: Int): FloatArray = synchronized(lock) {
        samples.copyOfRange(0, minOf(maxOf(upTo, 0), size))
    }

    fun drop(prefix: Int) {
        synchronized(lock) {
            val dropped = minOf(maxOf(prefix, 0), size)
            samples.copyInto(samples, 0, dropped, size)
            size -= dropped
            speechStarts = ArrayList(speechStarts.filter { it >= dropped }.map { it - dropped })
            lastSpeechEnd = lastSpeechEnd?.let { end -> if (end > dropped) end - dropped else null }
        }
    }
}
