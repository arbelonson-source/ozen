package com.arbelonson.ozen.core

class VoiceEvidence(private val score: (FloatArray) -> Float?) {
    private class Chunk(val end: Int, val voiced: Boolean?)

    private var pending = FloatArray(0)
    private var context = FloatArray(CONTEXT_SAMPLES)
    private val chunks = ArrayList<Chunk>()
    private var start = 0
    private var received = 0

    fun append(samples: FloatArray) {
        received += samples.size
        pending += samples
        while (pending.size >= CHUNK_SAMPLES) {
            val chunk = pending.copyOfRange(0, CHUNK_SAMPLES)
            pending = pending.copyOfRange(CHUNK_SAMPLES, pending.size)
            val probability = score(context + chunk)
            context = chunk.copyOfRange(CHUNK_SAMPLES - CONTEXT_SAMPLES, CHUNK_SAMPLES)
            chunks.add(Chunk(received - pending.size, probability?.let { it >= VOICE_THRESHOLD }))
        }
    }

    private fun overlapping(count: Int): List<Chunk> {
        val end = start + count
        return chunks.filter { it.end > start && it.end - CHUNK_SAMPLES < end }
    }

    fun hasVoice(inFirst: Int): Boolean? {
        val overlapping = overlapping(inFirst)
        if (overlapping.isEmpty()) return null
        if (overlapping.any { it.voiced == true }) return true
        return if (overlapping.any { it.voiced == null }) null else false
    }

    fun voicedChunks(inFirst: Int): Int? {
        val overlapping = overlapping(inFirst)
        if (overlapping.isEmpty() || overlapping.any { it.voiced == null }) return null
        return overlapping.count { it.voiced == true }
    }

    fun drop(prefix: Int) {
        start += prefix
        chunks.removeAll { it.end <= start }
    }

    companion object {
        const val CHUNK_SAMPLES = 4096
        const val CONTEXT_SAMPLES = 64
        const val VOICE_THRESHOLD = 0.5f
    }
}
