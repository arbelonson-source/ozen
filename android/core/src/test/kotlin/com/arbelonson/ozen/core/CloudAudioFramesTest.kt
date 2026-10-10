package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudAudioFramesTest {
    private fun floats(count: Int, value: Float) = FloatArray(count) { value }

    @Test
    fun `pieces under 50 ms wait for the next, and a piece of 50 ms goes at once`() {
        val pieces = CloudAudioFrames()
        assertTrue(pieces.add(floats(799, 0.1f)).isEmpty())
        assertEquals(listOf(800), pieces.add(floatArrayOf(0.2f)).map { it.size })
        assertEquals(listOf(800), pieces.add(floats(800, 0.1f)).map { it.size })
        assertNull(pieces.finish())
    }

    @Test
    fun `more than a second at once goes as pieces of a second, the rest held until there is 50 ms of it`() {
        val pieces = CloudAudioFrames()
        assertEquals(listOf(16_000, 16_000, 8_000), pieces.add(floats(40_000, 0.1f)).map { it.size })
        assertEquals(listOf(16_000), pieces.add(floats(16_500, 0.1f)).map { it.size })
        assertEquals(listOf(800), pieces.add(floats(300, 0.1f)).map { it.size })
    }

    @Test
    fun `the audio keeps its order, and what is left at the end is filled out to 50 ms with silence`() {
        val pieces = CloudAudioFrames()
        val sent = pieces.add(FloatArray(1_000) { it.toFloat() }) + pieces.add(FloatArray(500) { (1_000 + it).toFloat() })
        assertContentEquals(FloatArray(1_000) { it.toFloat() }, sent.flatMap { it.toList() }.toFloatArray())
        val last = assertNotNull(pieces.finish())
        assertEquals(800, last.size)
        assertContentEquals(FloatArray(500) { (1_000 + it).toFloat() }, last.copyOfRange(0, 500))
        assertTrue(last.drop(500).all { it == 0f })
        assertNull(pieces.finish())
    }

    @Test
    fun `a single sample left at the end still goes`() {
        val pieces = CloudAudioFrames()
        assertTrue(pieces.add(floatArrayOf(0.5f)).isEmpty())
        assertEquals(0.5f, pieces.finish()?.first())
    }
}
