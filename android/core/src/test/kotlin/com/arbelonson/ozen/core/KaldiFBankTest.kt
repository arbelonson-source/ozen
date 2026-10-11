package com.arbelonson.ozen.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KaldiFBankTest {
    private val fixtures = File("../../Tests/OzenPlatformTests/Fixtures")

    private fun wav(file: File): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(offset + it) }, Charsets.US_ASCII)
            val size = bytes.getInt(offset + 4)
            if (id == "data") return FloatArray(size / 2) { bytes.getShort(offset + 8 + it * 2) / 32768f }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in ${file.name}")
    }

    @Test
    fun `sound shorter than one frame gives nothing rather than a crash`() {
        assertNull(KaldiFBank().frames(floatArrayOf(0.1f, 0.2f, 0.3f), 16_000.0))
    }

    @Test
    fun `the same sound gives the same frames twice`() {
        val tone = FloatArray(24_000) { sin(2 * PI * 200 * it / 16_000).toFloat() }
        val first = assertNotNull(KaldiFBank().frames(tone, 16_000.0))
        val second = assertNotNull(KaldiFBank().frames(tone, 16_000.0))
        assertTrue(first.indices.all { first[it].contentEquals(second[it]) })
    }

    @Test
    fun `the first frames of a real clip match the reference library's own, as on the iPhone`() {
        val fixture = Json.parseToJsonElement(File(fixtures, "speaker_fixture.json").readText()).jsonObject
        val clip = fixture.getValue("clips").jsonObject.getValue("speakerA_clip1").jsonObject
        val samples = wav(File(fixtures, clip.getValue("wavFile").jsonPrimitive.content))
        val frames = assertNotNull(KaldiFBank().frames(samples, fixture.getValue("sampleRate").jsonPrimitive.int.toDouble()))
        assertEquals(clip.getValue("frameCount").jsonPrimitive.int, frames.size)
        for ((index, expectedFrame) in clip.getValue("firstFrames").jsonArray.withIndex()) {
            val expected = expectedFrame.jsonArray.map { it.jsonPrimitive.float }
            assertEquals(KaldiFBank.MEL_BIN_COUNT, frames[index].size)
            for (bin in expected.indices) {
                val tolerance = maxOf(abs(expected[bin]), 1f) * 0.01f
                assertTrue(abs(frames[index][bin] - expected[bin]) < tolerance, "frame $index bin $bin: got ${frames[index][bin]}, expected ${expected[bin]}")
            }
        }
    }
}
