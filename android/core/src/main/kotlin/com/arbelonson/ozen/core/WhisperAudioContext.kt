package com.arbelonson.ozen.core

object WhisperAudioContext {
    const val FULL_FRAMES = 1_500
    const val SAMPLES_PER_FRAME = 320
    const val MARGIN_FRAMES = 50
    const val FINAL_MARGIN_FRAMES = 250

    fun frames(sampleCount: Int, isFinal: Boolean): Int {
        val margin = if (isFinal) FINAL_MARGIN_FRAMES else MARGIN_FRAMES
        return minOf(FULL_FRAMES, (sampleCount + SAMPLES_PER_FRAME - 1) / SAMPLES_PER_FRAME + margin)
    }
}
