package com.arbelonson.ozen.core

import kotlin.math.max
import kotlin.math.min

internal class AudioIntake(voiceScorer: ((FloatArray) -> Float?)?, private val pauseSamples: Int? = null) {
    class Status(
        val count: Int,
        val lastSpeechEnd: Int?,
        val finished: Boolean,
        val firstSpeechStart: Int? = null,
        val pauses: List<Int> = emptyList(),
    )

    private class Pause(val end: Int, val resume: Int)

    private val lock = Any()
    private var samples = FloatArray(16_000 * 30)
    private var size = 0
    private var lastSpeechEnd: Int? = null
    private var firstSpeechStart: Int? = null
    private var pauses = ArrayList<Pause>()
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
        val start = max(end - block.size, 0)
        val last = lastSpeechEnd
        if (last == null) {
            firstSpeechStart = start
        } else if (pauseSamples != null && start - last >= pauseSamples) {
            pauses.add(Pause(last, start))
        }
        lastSpeechEnd = end
    }

    fun hasVoice(upTo: Int): Boolean? = synchronized(lock) { evidence?.hasVoice(upTo) }

    fun voicedChunks(upTo: Int): Int? = synchronized(lock) { evidence?.voicedChunks(upTo) }

    fun markFinished() {
        synchronized(lock) { finished = true }
    }

    fun status(): Status = synchronized(lock) {
        Status(size, lastSpeechEnd, finished, firstSpeechStart, pauses.map { it.end })
    }

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
            val resumed = pauses.lastOrNull { it.end <= dropped }?.resume?.takeIf { it > dropped }
            pauses = pauses.filter { it.end > dropped }.mapTo(ArrayList()) { Pause(it.end - dropped, it.resume - dropped) }
            firstSpeechStart = when {
                lastSpeechEnd == null -> null
                resumed != null -> resumed - dropped
                else -> firstSpeechStart?.let { max(it - dropped, 0) }
            }
        }
    }
}
