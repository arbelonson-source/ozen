package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileLinePass
import com.arbelonson.ozen.core.SpeechStretch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class FileSpeechCutterTest {
    private val rate = 16_000
    private val pad = (FileLinePass.PAD_SECONDS * rate).toInt()

    private fun voiced(chunk: FloatArray) = FloatArray(chunk.size / 512) { frame ->
        if ((0 until 512).any { chunk[frame * 512 + it] != 0f }) 1f else 0f
    }

    private fun cut(vararg pieces: FloatArray): List<Pair<SpeechStretch, FloatArray>> = runBlocking {
        val taken = ArrayList<Pair<SpeechStretch, FloatArray>>()
        val cutter = FileSpeechCutter(maxStretchSeconds = 20.0, score = ::voiced)
        for (piece in pieces) cutter.add(piece) { stretch, window -> taken += stretch to window }
        cutter.finish { stretch, window -> taken += stretch to window }
        taken
    }

    @Test
    fun speechStartingInAPieceTailNotYetScoredKeepsItsLeadInAndItsFirstSound() {
        val quiet = 10 * 4_096
        val tail = 4_000
        val first = FloatArray(quiet) + FloatArray(tail) { 0.5f }
        val second = FloatArray(rate) { 0.5f } + FloatArray(2 * rate)

        val (stretch, window) = cut(first, second).single()

        assertEquals(quiet.toDouble() / rate, stretch.startSeconds, 1e-9)
        val spoken = tail + rate
        assertEquals(spoken, window.count { it != 0f })
        assertEquals(List(pad) { 0f }, window.take(pad))
        assertEquals(0.5f, window[pad])
    }

    @Test
    fun speechAfterWholeChunksIsCutTheSameWay() {
        val quiet = 10 * 4_096
        val (stretch, window) = cut(FloatArray(quiet), FloatArray(rate) { 0.5f } + FloatArray(2 * rate)).single()

        assertEquals(quiet.toDouble() / rate, stretch.startSeconds, 1e-9)
        assertEquals(rate, window.count { it != 0f })
        assertEquals(List(pad) { 0f }, window.take(pad))
    }
}
