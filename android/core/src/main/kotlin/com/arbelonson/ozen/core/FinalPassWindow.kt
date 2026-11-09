package com.arbelonson.ozen.core

class FinalPassWindow {
    private val secondsPerFrame = ArrayDeque<Double>()

    fun recordLivePass(frames: Int, seconds: Double) {
        if (frames <= 0 || !seconds.isFinite() || seconds <= 0) return
        secondsPerFrame.addLast(seconds / frames)
        while (secondsPerFrame.size > REMEMBERED_PASSES) secondsPerFrame.removeFirst()
    }

    val fullWindowSeconds: Double? get() = secondsPerFrame.maxOrNull()?.times(WhisperAudioContext.FULL_FRAMES)

    fun usesFullWindow(heat: DeviceHeat, lowPowerMode: Boolean, attempts: Int): Boolean {
        val seconds = fullWindowSeconds ?: return false
        return seconds * attempts.coerceAtLeast(1) <= BUDGET_SECONDS && !lowPowerMode && heat.rawValue < DeviceHeat.Serious.rawValue
    }

    companion object {
        const val BUDGET_SECONDS = 1.5
        const val REMEMBERED_PASSES = 5
    }
}
