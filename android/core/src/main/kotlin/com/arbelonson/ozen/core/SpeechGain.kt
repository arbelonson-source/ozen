package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SpeechGain {
    const val TARGET_PEAK = 0.5f
    const val MAXIMUM_GAIN = 100f

    fun gain(samples: FloatArray): Float {
        val loud = level(samples)
        if (!(loud > 0)) return 1f
        return min(max(TARGET_PEAK / loud, 1f), MAXIMUM_GAIN)
    }

    fun normalized(samples: FloatArray): FloatArray {
        val gain = gain(samples)
        if (!(gain > 1)) return samples
        return FloatArray(samples.size) { min(max(samples[it] * gain, -1f), 1f) }
    }

    private fun level(samples: FloatArray): Float {
        var count = 0
        var index = 0
        while (index < samples.size) {
            if (samples[index].isFinite()) count += 1
            index += 4
        }
        if (count == 0) return Float.NaN
        val kept = count - min(count - 1, (count.toFloat() * 0.999f).toInt())
        val loudest = FloatArray(kept)
        var held = 0
        index = 0
        while (index < samples.size) {
            val sample = samples[index]
            index += 4
            if (!sample.isFinite()) continue
            val magnitude = abs(sample)
            if (held < kept) {
                rise(loudest, held, magnitude)
                held += 1
            } else if (magnitude > loudest[0]) {
                sink(loudest, kept, magnitude)
            }
        }
        return loudest[0]
    }

    private fun rise(heap: FloatArray, at: Int, value: Float) {
        var child = at
        while (child > 0) {
            val parent = (child - 1) / 2
            if (heap[parent] <= value) break
            heap[child] = heap[parent]
            child = parent
        }
        heap[child] = value
    }

    private fun sink(heap: FloatArray, size: Int, value: Float) {
        var parent = 0
        while (true) {
            var child = 2 * parent + 1
            if (child >= size) break
            if (child + 1 < size && heap[child + 1] < heap[child]) child += 1
            if (heap[child] >= value) break
            heap[parent] = heap[child]
            parent = child
        }
        heap[parent] = value
    }
}
