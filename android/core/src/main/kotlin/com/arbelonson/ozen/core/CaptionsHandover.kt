package com.arbelonson.ozen.core

class CaptionsHandover {
    var pausedHere = false
        private set

    fun begin(phase: PipelinePhase): Boolean {
        if (pausedHere || !phase.isListening) return false
        pausedHere = true
        return true
    }

    fun end(phase: PipelinePhase): Boolean {
        if (!pausedHere) return false
        pausedHere = false
        return phase == PipelinePhase.Paused
    }

    fun cancel(): Boolean {
        val was = pausedHere
        pausedHere = false
        return was
    }
}
