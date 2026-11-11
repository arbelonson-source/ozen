package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileLinePass
import com.arbelonson.ozen.core.SpeechStretch
import com.arbelonson.ozen.core.SpeechStretchPlanner

internal class FileSpeechCutter(maxStretchSeconds: Double, private val score: (FloatArray) -> FloatArray) {
    private val planner = SpeechStretchPlanner(maxStretchSeconds = maxStretchSeconds)
    private val audio = RollingAudio(SAMPLE_RATE)
    private var pending = FloatArray(0)
    private var scoredSamples = 0L

    suspend fun add(piece: FloatArray, take: suspend (SpeechStretch, FloatArray) -> Unit) {
        audio.append(piece)
        pending += piece
        val usable = pending.size / CHUNK_SAMPLES * CHUNK_SAMPLES
        if (usable == 0) return
        val chunk = pending.copyOf(usable)
        pending = pending.copyOfRange(usable, pending.size)
        handle(scored(chunk), take)
    }

    suspend fun finish(take: suspend (SpeechStretch, FloatArray) -> Unit) {
        if (pending.isNotEmpty()) {
            val tail = pending.copyOf(CHUNK_SAMPLES)
            pending = FloatArray(0)
            handle(scored(tail), take)
        }
        handle(planner.finish(), take)
    }

    private fun scored(chunk: FloatArray): List<SpeechStretch> {
        scoredSamples += chunk.size
        return planner.add(score(chunk))
    }

    private suspend fun handle(stretches: List<SpeechStretch>, take: suspend (SpeechStretch, FloatArray) -> Unit) {
        for (stretch in stretches) {
            val window = audio.between(stretch.startSeconds - FileLinePass.PAD_SECONDS, stretch.endSeconds + FileLinePass.PAD_SECONDS) ?: continue
            take(stretch, window)
        }
        audio.keepFrom((planner.openStartSeconds ?: scoredSamples.toDouble() / SAMPLE_RATE) - FileLinePass.PAD_SECONDS)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 4_096
    }
}
