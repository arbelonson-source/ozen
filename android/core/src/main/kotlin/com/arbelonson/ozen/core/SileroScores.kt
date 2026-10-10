package com.arbelonson.ozen.core

object SileroScores {
    fun noisyOr(frames: FloatArray): Float? {
        if (frames.isEmpty() || frames.any { it.isNaN() || it < 0f || it > 1f }) return null
        var silent = 1.0
        for (frame in frames) silent *= 1.0 - frame
        return (1.0 - silent).toFloat()
    }
}
