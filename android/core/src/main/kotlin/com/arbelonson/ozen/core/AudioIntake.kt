package com.arbelonson.ozen.core

import kotlin.math.max
import kotlin.math.min

internal class AudioIntake(voiceScorer: ((FloatArray) -> Float?)?) {
    class Status(val count: Int, val lastSpeechEnd: Int?, val finished: Boolean, val firstSpeechStart: Int? = null)

    private val lock = Any()
    private var samples = FloatArray(16_000 * 30)
    private var size = 0
    private var lastSpeechEnd: Int? = null
    private var firstSpeechStart: Int? = null
    private var finished = false
    private val detector = EnergyVoiceDetector.forWhisperLines()
    private val block = FloatArray(EnergyVoiceDetector.BLOCK_SAMPLES)
    private var blockFill = 0
    private val evidence: VoiceEvidence? = voiceScorer?.let { VoiceEvidence(it) }

    fun append(chunk: FloatArray) {
        synchronized(lock) {
            if (size + chunk.size > samples.size) {
                samples = samples.copyOf(max(samples.size * 2, size + chunk.size))
            }
            chunk.copyInto(samples, size)
            size += chunk.size
            var read = 0
            while (read < chunk.size) {
                val taken = min(block.size - blockFill, chunk.size - read)
                chunk.copyInto(block, blockFill, read, read + taken)
                blockFill += taken
                read += taken
                if (blockFill == block.size) {
                    blockFill = 0
                    readBlock(end = size - chunk.size + read)
                }
            }
            evidence?.append(chunk)
        }
    }

    private fun readBlock(end: Int) {
        if (!detector.isSpeech(block)) return
        if (lastSpeechEnd == null) firstSpeechStart = max(end - block.size, 0)
        lastSpeechEnd = end
    }

    fun hasVoice(upTo: Int): Boolean? = synchronized(lock) { evidence?.hasVoice(upTo) }

    fun voicedChunks(upTo: Int): Int? = synchronized(lock) { evidence?.voicedChunks(upTo) }

    fun markFinished() {
        synchronized(lock) { finished = true }
    }

    fun status(): Status = synchronized(lock) { Status(size, lastSpeechEnd, finished, firstSpeechStart) }

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
            firstSpeechStart = if (lastSpeechEnd == null) null else firstSpeechStart?.let { max(it - dropped, 0) }
        }
    }
}
