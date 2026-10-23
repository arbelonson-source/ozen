package com.arbelonson.ozen

class RollingAudio(private val sampleRate: Int = 16_000) {
    private var samples = FloatArray(0)
    private var first = 0L

    val endSeconds: Double get() = (first + samples.size).toDouble() / sampleRate

    fun append(piece: FloatArray) {
        samples += piece
    }

    fun between(startSeconds: Double, endSeconds: Double): FloatArray? {
        val from = maxOf(first, (startSeconds * sampleRate).toLong())
        val to = minOf(first + samples.size, (endSeconds * sampleRate).toLong())
        if (to <= from) return null
        return samples.copyOfRange((from - first).toInt(), (to - first).toInt())
    }

    fun keepFrom(seconds: Double) {
        val drop = ((seconds * sampleRate).toLong() - first).coerceIn(0, samples.size.toLong()).toInt()
        if (drop == 0) return
        samples = samples.copyOfRange(drop, samples.size)
        first += drop
    }
}
