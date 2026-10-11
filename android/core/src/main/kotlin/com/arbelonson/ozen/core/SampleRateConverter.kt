package com.arbelonson.ozen.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object SampleRateConverter {
    private const val ZERO_CROSSINGS = 16

    fun convert(samples: FloatArray, from: Int, to: Int = 16_000): FloatArray {
        require(from > 0 && to > 0)
        if (from == to || samples.isEmpty()) return samples.copyOf()
        val shared = greatestCommonDivisor(from, to)
        val up = to / shared
        val down = from / shared
        val cutoff = 0.5 * min(1.0, to.toDouble() / from) * 0.97
        val halfWidth = ceil(ZERO_CROSSINGS / (2 * cutoff)).toInt()
        val reach = halfWidth + 1.0
        val kernels = Array(up) { phase ->
            FloatArray(2 * halfWidth + 1) { tap ->
                val distance = tap - halfWidth + phase.toDouble() / up
                val window = 0.42 + 0.5 * cos(PI * distance / reach) + 0.08 * cos(2 * PI * distance / reach)
                val argument = 2 * cutoff * distance
                val sinc = if (argument == 0.0) 1.0 else sin(PI * argument) / (PI * argument)
                (2 * cutoff * sinc * window).toFloat()
            }
        }
        val count = ((samples.size.toLong() * up + down - 1) / down).toInt()
        return FloatArray(count) { index ->
            val position = index.toLong() * down
            val centre = (position / up).toInt()
            val kernel = kernels[(position % up).toInt()]
            var sum = 0.0
            for (tap in kernel.indices) {
                val source = centre - (tap - halfWidth)
                if (source in samples.indices) sum += samples[source] * kernel[tap]
            }
            sum.toFloat()
        }
    }

    private tailrec fun greatestCommonDivisor(a: Int, b: Int): Int = if (b == 0) a else greatestCommonDivisor(b, a % b)
}
