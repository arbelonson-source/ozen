package com.arbelonson.ozen.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object SampleRateConverter {
    private const val ZERO_CROSSINGS = 16
    private const val BLOCK = 16_384

    fun convert(samples: FloatArray, from: Int, to: Int = 16_000): FloatArray {
        require(from > 0 && to > 0)
        if (from == to || samples.isEmpty()) return samples.copyOf()
        val filter = Filter(from, to)
        val total = samples.size.toLong()
        return FloatArray(filter.outputCount(total)) { index -> filter.sample(index.toLong(), samples, 0, total) }
    }

    /**
     * [convert] a piece at a time, for sound too long to hold whole at its
     * own rate (ten minutes at 48 kHz is 115 MB): the same samples come out
     * as from one call.
     */
    class Stream(from: Int, to: Int = 16_000) {
        private val filter = if (from == to) null else Filter(from, to)
        private var pending = FloatArray(0)
        private var pendingSize = 0
        private var first = 0L
        private var received = 0L
        private var produced = 0L
        private val blocks = ArrayList<FloatArray>()
        private var block = FloatArray(BLOCK)
        private var inBlock = 0

        init {
            require(from > 0 && to > 0)
        }

        fun add(samples: FloatArray) {
            val filter = filter ?: return samples.forEach(::put)
            if (pendingSize + samples.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + samples.size))
            samples.copyInto(pending, pendingSize)
            pendingSize += samples.size
            received += samples.size
            while (filter.centre(produced) + filter.halfWidth < received) put(filter.sample(produced++, pending, first, received))
            val drop = (filter.centre(produced) - filter.halfWidth - first).coerceIn(0, pendingSize.toLong()).toInt()
            pending.copyInto(pending, 0, drop, pendingSize)
            pendingSize -= drop
            first += drop
        }

        fun finish(): FloatArray {
            filter?.let { while (produced < it.outputCount(received)) put(it.sample(produced++, pending, first, received)) }
            return drain()
        }

        /** What has been converted so far and not handed over yet, so a long recording can be passed on piece by piece. */
        fun drain(): FloatArray {
            val all = FloatArray(blocks.size * BLOCK + inBlock)
            blocks.forEachIndexed { index, full -> full.copyInto(all, index * BLOCK) }
            block.copyInto(all, blocks.size * BLOCK, 0, inBlock)
            blocks.clear()
            inBlock = 0
            return all
        }

        private fun put(value: Float) {
            if (inBlock == BLOCK) {
                blocks += block
                block = FloatArray(BLOCK)
                inBlock = 0
            }
            block[inBlock++] = value
        }
    }

    private class Filter(from: Int, to: Int) {
        private val up: Int
        private val down: Int
        val halfWidth: Int
        private val kernels: Array<FloatArray>

        init {
            val shared = greatestCommonDivisor(from, to)
            up = to / shared
            down = from / shared
            val cutoff = 0.5 * min(1.0, to.toDouble() / from) * 0.97
            halfWidth = ceil(ZERO_CROSSINGS / (2 * cutoff)).toInt()
            val reach = halfWidth + 1.0
            kernels = Array(up) { phase ->
                FloatArray(2 * halfWidth + 1) { tap ->
                    val distance = tap - halfWidth + phase.toDouble() / up
                    val window = 0.42 + 0.5 * cos(PI * distance / reach) + 0.08 * cos(2 * PI * distance / reach)
                    val argument = 2 * cutoff * distance
                    val sinc = if (argument == 0.0) 1.0 else sin(PI * argument) / (PI * argument)
                    (2 * cutoff * sinc * window).toFloat()
                }
            }
        }

        fun outputCount(inputs: Long): Int = ((inputs * up + down - 1) / down).toInt()

        fun centre(index: Long): Long = index * down / up

        fun sample(index: Long, samples: FloatArray, first: Long, total: Long): Float {
            val position = index * down
            val centre = position / up
            val kernel = kernels[(position % up).toInt()]
            var sum = 0.0
            for (tap in kernel.indices) {
                val source = centre - (tap - halfWidth)
                if (source in 0 until total) sum += samples[(source - first).toInt()] * kernel[tap]
            }
            return sum.toFloat()
        }
    }

    private tailrec fun greatestCommonDivisor(a: Int, b: Int): Int = if (b == 0) a else greatestCommonDivisor(b, a % b)
}
