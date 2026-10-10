package com.arbelonson.ozen.core

object ControlBarAutoHide {
    const val IDLE_SECONDS: Double = 5.0

    fun hides(
        enabled: Boolean,
        isListening: Boolean,
        followingLatest: Boolean,
        hasLines: Boolean,
        voiceOverRunning: Boolean,
        pausedForCall: Boolean = false,
        lastTouchAt: Double,
        now: Double,
    ): Boolean {
        if (!enabled || !isListening || !followingLatest || !hasLines || voiceOverRunning || pausedForCall) return false
        return now - lastTouchAt >= IDLE_SECONDS
    }
}
