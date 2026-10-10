package com.arbelonson.ozen.core

class CloudAudioFrames {
    private var held = FloatArray(0)

    fun add(samples: FloatArray): List<FloatArray> {
        held += samples
        val frames = ArrayList<FloatArray>()
        while (held.size >= SHORTEST) {
            val size = minOf(held.size, LONGEST)
            frames.add(held.copyOfRange(0, size))
            held = held.copyOfRange(size, held.size)
        }
        return frames
    }

    fun finish(): FloatArray? {
        if (held.isEmpty()) return null
        val last = held + FloatArray(maxOf(0, SHORTEST - held.size))
        held = FloatArray(0)
        return last
    }

    companion object {
        const val SHORTEST = CloudSpeechEngine.SAMPLE_RATE / 20
        const val LONGEST = CloudSpeechEngine.SAMPLE_RATE
    }
}
