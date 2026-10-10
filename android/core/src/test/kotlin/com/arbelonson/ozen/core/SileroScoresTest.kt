package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SileroScoresTest {
    @Test
    fun `a chunk is voiced as likely as any of its frames heard a voice`() {
        assertEquals(0.75f, SileroScores.noisyOr(floatArrayOf(0.5f, 0.5f))!!, 1e-6f)
        assertEquals(0.0f, SileroScores.noisyOr(FloatArray(8))!!, 1e-6f)
        assertEquals(1.0f, SileroScores.noisyOr(floatArrayOf(0f, 0f, 1f, 0f))!!, 1e-6f)
        assertEquals(1 - 0.9f * 0.8f * 0.7f, SileroScores.noisyOr(floatArrayOf(0.1f, 0.2f, 0.3f))!!, 1e-6f)
    }

    @Test
    fun `eight quiet frames add up to more than any one of them`() {
        val frames = FloatArray(8) { 0.1f }
        assertEquals(1 - Math.pow(0.9, 8.0).toFloat(), SileroScores.noisyOr(frames)!!, 1e-6f)
    }

    @Test
    fun `no frames or a frame that is not a probability gives no score`() {
        assertNull(SileroScores.noisyOr(FloatArray(0)))
        assertNull(SileroScores.noisyOr(floatArrayOf(0.2f, Float.NaN)))
        assertNull(SileroScores.noisyOr(floatArrayOf(1.5f)))
        assertNull(SileroScores.noisyOr(floatArrayOf(-0.1f)))
    }
}
