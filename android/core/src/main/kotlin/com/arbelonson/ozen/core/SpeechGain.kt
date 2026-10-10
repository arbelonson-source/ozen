package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SpeechGain {
    const val TARGET_PEAK = 0.5f
    const val MAXIMUM_GAIN = 100f

    fun gain(samples: FloatArray): Float {
        val magnitudes = ArrayList<Float>(samples.size / 4 + 1)
        var index = 0
        while (index < samples.size) {
            if (samples[index].isFinite()) magnitudes.add(abs(samples[index]))
            index += 4
        }
        if (magnitudes.isEmpty()) return 1f
        magnitudes.sort()
        val loud = magnitudes[min(magnitudes.size - 1, (magnitudes.size.toFloat() * 0.999f).toInt())]
        if (!(loud > 0)) return 1f
        return min(max(TARGET_PEAK / loud, 1f), MAXIMUM_GAIN)
    }

    fun normalized(samples: FloatArray): FloatArray {
        val gain = gain(samples)
        if (!(gain > 1)) return samples
        return FloatArray(samples.size) { min(max(samples[it] * gain, -1f), 1f) }
    }
}
