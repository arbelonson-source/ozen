package com.arbelonson.ozen.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

class AudioLevelHistogram {
    private var counts = IntArray(STEP_COUNT)

    var total = 0
        private set

    fun add(rms: Float) {
        val decibels = if (rms > 0) 20 * log10(rms.toDouble()) else Double.NEGATIVE_INFINITY
        val step = if (decibels.isFinite()) {
            floor((decibels - LOWEST_DECIBELS) / STEP_DECIBELS).toInt()
        } else {
            0
        }
        counts[min(max(step, 0), STEP_COUNT - 1)] += 1
        total += 1
    }

    fun decibels(atFraction: Double): Int? {
        if (total <= 0) return null
        val wanted = max(1, ceil(min(max(atFraction, 0.0), 1.0) * total.toDouble()).toInt())
        var seen = 0
        for ((step, count) in counts.withIndex()) {
            seen += count
            if (seen >= wanted) return LOWEST_DECIBELS + (step + 1) * STEP_DECIBELS
        }
        return 0
    }

    val summary: String?
        get() {
            val quiet = decibels(0.1) ?: return null
            val middle = decibels(0.5) ?: return null
            val loud = decibels(0.9) ?: return null
            return "quiet $quiet / middle $middle / loud $loud dBFS"
        }

    fun copy(): AudioLevelHistogram {
        val copy = AudioLevelHistogram()
        copy.counts = counts.copyOf()
        copy.total = total
        return copy
    }

    override fun equals(other: Any?): Boolean =
        other is AudioLevelHistogram && total == other.total && counts.contentEquals(other.counts)

    override fun hashCode(): Int = 31 * total + counts.contentHashCode()

    companion object {
        const val LOWEST_DECIBELS = -100
        const val STEP_DECIBELS = 2
        private const val STEP_COUNT = 50
    }
}
