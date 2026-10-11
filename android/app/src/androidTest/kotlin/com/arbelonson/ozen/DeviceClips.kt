package com.arbelonson.ozen

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DeviceClips {
    fun readWav(file: File): FloatArray = readWav(file.readBytes(), file.name)

    fun asset(name: String): FloatArray =
        readWav(InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }, name)

    private fun readWav(wav: ByteArray, name: String): FloatArray {
        val bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(offset + it) }, Charsets.US_ASCII)
            val size = bytes.getInt(offset + 4)
            if (id == "data") return FloatArray(size / 2) { bytes.getShort(offset + 8 + it * 2) / 32768f }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in $name")
    }

    fun writeWav(file: File, frames: FloatArray, rate: Int = 16_000, channels: Int = 1) {
        val bytes = frames.size * channels * 2
        val data = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
        data.putShort((channels * 2).toShort()).putShort(16)
        data.put("data".toByteArray()).putInt(bytes)
        for (value in frames) repeat(channels) { data.putShort((value.coerceIn(-1f, 1f) * 32_767).toInt().toShort()) }
        file.writeBytes(data.array())
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var left = 0.0
        var right = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            left += a[i] * a[i]
            right += b[i] * b[i]
        }
        return dot / kotlin.math.sqrt(left) / kotlin.math.sqrt(right)
    }

    fun withoutPauses(samples: FloatArray): FloatArray {
        val frames = samples.toList().chunked(1_600).map { it.toFloatArray() }
        val loudness = frames.map { frame -> kotlin.math.sqrt(frame.sumOf { (it * it).toDouble() } / frame.size) }
        val voiced = loudness.sorted()[loudness.size / 2] * 0.3
        return frames.filterIndexed { index, _ -> loudness[index] >= voiced }.flatMap { it.asList() }.toFloatArray()
    }

    fun wordErrorRate(reference: String, hypothesis: String): Double {
        fun words(text: String) = text.replace(Regex("[\\u0591-\\u05C7]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val said = words(reference)
        val heard = words(hypothesis)
        var row = IntArray(heard.size + 1) { it }
        for ((i, word) in said.withIndex()) {
            val next = IntArray(heard.size + 1)
            next[0] = i + 1
            for ((j, other) in heard.withIndex()) {
                next[j + 1] = minOf(row[j + 1] + 1, next[j] + 1, row[j] + if (word == other) 0 else 1)
            }
            row = next
        }
        return row[heard.size].toDouble() / maxOf(said.size, 1)
    }
}
