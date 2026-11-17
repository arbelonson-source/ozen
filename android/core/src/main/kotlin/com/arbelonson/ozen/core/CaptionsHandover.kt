package com.arbelonson.ozen.core

/**
 * Captions handed over to a recording that is turned into text. Running
 * captions are paused and resumed. A start still loading, or one waiting
 * for a retry, is stopped and started again, and a start that is not
 * going on at all (idle, a failure nothing retries) is left alone.
 */
class CaptionsHandover {
    var pausedHere = false
        private set

    private var startedAgain = false

    fun begin(phase: PipelinePhase, waitingForRetry: Boolean = false): Boolean {
        if (pausedHere) return false
        val loading = phase.isTransitioning || (phase is PipelinePhase.Failed && waitingForRetry)
        if (!phase.isListening && !loading) return false
        pausedHere = true
        startedAgain = loading
        return true
    }

    fun end(phase: PipelinePhase): Boolean {
        if (!pausedHere) return false
        pausedHere = false
        return phase == if (startedAgain) PipelinePhase.Idle else PipelinePhase.Paused
    }

    fun cancel(): Boolean {
        val was = pausedHere
        pausedHere = false
        return was
    }
}
