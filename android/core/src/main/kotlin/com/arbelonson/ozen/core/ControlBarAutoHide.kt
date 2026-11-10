package com.arbelonson.ozen.core

object ControlBarAutoHide {
    const val IDLE_SECONDS: Double = 5.0

    fun hides(
        enabled: Boolean,
        isListening: Boolean,
        followingLatest: Boolean,
        hasLines: Boolean,
        voiceOverRunning: Boolean,
        switchControlRunning: Boolean = false,
        pausedForCall: Boolean = false,
        lastTouchAt: Double,
        now: Double,
    ): Boolean {
        if (!enabled || !isListening || !followingLatest || !hasLines || voiceOverRunning || switchControlRunning || pausedForCall) return false
        return now - lastTouchAt >= IDLE_SECONDS
    }
}
