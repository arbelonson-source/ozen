package com.arbelonson.ozen.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class WAVFileTest {
    private fun buffer(data: ByteArray): ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    private fun uint32(data: ByteArray, at: Int): Long = buffer(data).getInt(at).toLong() and 0xFFFFFFFFL

    private fun int16(data: ByteArray, at: Int): Short = buffer(data).getShort(at)

    private fun text(data: ByteArray, from: Int, to: Int): String = String(data, from, to - from, Charsets.UTF_8)

    @Test
    fun `a mono 16-bit header that says how much audio follows`() {
        val wav = WAVFile.pcm16(floatArrayOf(0f, 0.5f, -0.5f), sampleRate = 16_000)
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", text(wav, 0, 4))
        assertEquals(36L + 6, uint32(wav, 4))
        assertEquals("WAVEfmt ", text(wav, 8, 16))
        assertEquals(16L, uint32(wav, 16))
        assertEquals(1.toShort(), int16(wav, 20))
        assertEquals(1.toShort(), int16(wav, 22))
        assertEquals(16_000L, uint32(wav, 24))
        assertEquals(32_000L, uint32(wav, 28))
        assertEquals(2.toShort(), int16(wav, 32))
        assertEquals(16.toShort(), int16(wav, 34))
        assertEquals("data", text(wav, 36, 40))
        assertEquals(6L, uint32(wav, 40))
    }

    @Test
    fun `samples become 16-bit values, too loud clipped and damaged ones silent`() {
        val wav = WAVFile.pcm16(floatArrayOf(1f, -1f, 0.5f, 3f, -3f, Float.NaN, Float.POSITIVE_INFINITY), sampleRate = 16_000)
        val values = (0 until 7).map { int16(wav, 44 + it * 2).toInt() }
        assertEquals(listOf(32_767, -32_767, 16_384, 32_767, -32_767, 0, 0), values)
    }

    @Test
    fun `no audio is still a readable file`() {
        val wav = WAVFile.pcm16(FloatArray(0), sampleRate = 16_000)
        assertEquals(44, wav.size)
        assertEquals(0L, uint32(wav, 40))
    }
}
