package com.arbelonson.ozen.core

object ScreenAwakePolicy {
    const val quietLockSeconds: Double = 15.0 * 60

    fun shouldKeepAwake(
        phase: PipelinePhase,
        keepAwakeWhileListening: Boolean,
        lastActivityAt: Double? = null,
        now: Double = 0.0,
    ): Boolean {
        if (phase.isTransitioning) return true
        if (!(phase.isListening && keepAwakeWhileListening)) return false
        if (lastActivityAt == null) return true
        return now - lastActivityAt < quietLockSeconds
    }
}
