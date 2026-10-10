package com.arbelonson.ozen.core

import kotlin.math.max
import kotlin.math.min

data class FinishedLine(val end: Int, val cut: Boolean)

object UtteranceCut {
    fun finishedLine(total: Int, speechEnd: Int, pad: Int, maxSamples: Int, stillTalkingAtCap: Boolean): FinishedLine {
        val end = min(total, speechEnd + pad)
        if (!(stillTalkingAtCap || end > maxSamples)) return FinishedLine(end, false)
        return FinishedLine(min(end, maxSamples), true)
    }

    fun quietestPoint(samples: FloatArray, before: Int, lookBack: Int, frame: Int): Int {
        val end = min(max(before, 0), samples.size)
        val start = max(0, end - max(lookBack, 0))
        if (!(frame > 1 && end - start >= frame)) return end
        val step = frame / 2
        var best = end
        var bestEnergy = Float.POSITIVE_INFINITY
        var index = start
        while (index + frame <= end) {
            var energy = 0f
            for (i in index until index + frame) {
                energy += samples[i] * samples[i]
            }
            if (energy <= bestEnergy) {
                bestEnergy = energy
                best = index + step
            }
            index += step
        }
        return best
    }
}
