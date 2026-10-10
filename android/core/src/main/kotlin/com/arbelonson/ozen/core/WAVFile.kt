package com.arbelonson.ozen.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.truncate

object WAVFile {
    fun pcm16(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        for (sample in samples) {
            val clamped = if (sample.isFinite()) min(max(sample, -1f), 1f) else 0f
            buffer.putShort(roundHalfAwayFromZero(clamped * Short.MAX_VALUE.toFloat()).toShort())
        }
        return buffer.array()
    }

    private fun roundHalfAwayFromZero(value: Float): Int {
        val whole = truncate(value)
        return (if (abs(value - whole) >= 0.5f) whole + sign(value) else whole).toInt()
    }
}
