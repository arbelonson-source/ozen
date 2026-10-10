package com.arbelonson.ozen.core

class DownloadEstimator {
    private data class Sample(val time: Double, val fraction: Double)

    private val samples = ArrayList<Sample>()

    fun record(fraction: Double, time: Double) {
        val clamped = minOf(maxOf(fraction, 0.0), 1.0)
        val last = samples.lastOrNull()
        if (last != null && clamped < last.fraction - 0.01) {
            samples.clear()
        }
        if (clamped > 0 && samples.all { it.fraction == 0.0 }) {
            samples.clear()
        }
        samples.add(Sample(time, clamped))
        while (samples.size > 2 && time - samples[0].time >= WINDOW_SECONDS) {
            samples.removeAt(0)
        }
    }

    fun secondsRemaining(): Double? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.lastOrNull() ?: return null
        val span = last.time - first.time
        val progress = last.fraction - first.fraction
        if (span < MINIMUM_SPAN_SECONDS || progress <= 0) return null
        return (1 - last.fraction) / (progress / span)
    }

    fun quietLimit(floor: Double, gaps: Double): Double {
        if (samples.size <= 1) return floor
        return maxOf(floor, gaps * (samples.last().time - samples.first().time) / (samples.size - 1).toDouble())
    }

    fun reset() {
        samples.clear()
    }

    override fun equals(other: Any?): Boolean = other is DownloadEstimator && other.samples == samples

    override fun hashCode(): Int = samples.hashCode()

    companion object {
        const val WINDOW_SECONDS = 30.0
        const val MINIMUM_SPAN_SECONDS = 5.0
    }
}
