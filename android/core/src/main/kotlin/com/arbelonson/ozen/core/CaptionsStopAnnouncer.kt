package com.arbelonson.ozen.core

class CaptionsStopAnnouncer {
    enum class Event { Stopped, Back, Ready }

    private var stoppedByFailure = false
    private var startBegan: Double? = null
    private var pendingStop = false

    fun phaseChanged(phase: PipelinePhase, active: Boolean = true, seconds: Double = 0.0): Event? = when (phase) {
        is PipelinePhase.Failed -> {
            if (stoppedByFailure) {
                if (pendingStop && active) {
                    pendingStop = false
                    Event.Stopped
                } else {
                    null
                }
            } else {
                stoppedByFailure = true
                startBegan = null
                pendingStop = !active
                Event.Stopped
            }
        }
        PipelinePhase.Listening -> {
            pendingStop = false
            val began = startBegan
            startBegan = null
            if (stoppedByFailure) {
                stoppedByFailure = false
                Event.Back
            } else if (began != null && seconds - began >= LONG_PREPARATION_SECONDS) {
                Event.Ready
            } else {
                null
            }
        }
        PipelinePhase.Idle, PipelinePhase.Paused -> {
            stoppedByFailure = false
            pendingStop = false
            startBegan = null
            null
        }
        PipelinePhase.RequestingMicrophonePermission, is PipelinePhase.PreparingEngine, PipelinePhase.StartingAudio -> {
            if (startBegan == null) startBegan = seconds
            null
        }
    }

    fun comingBack(phase: PipelinePhase): Event? {
        val owed = pendingStop && phase is PipelinePhase.Failed
        if (phase is PipelinePhase.Failed) pendingStop = false
        return if (owed) Event.Stopped else null
    }

    companion object {
        const val LONG_PREPARATION_SECONDS = 10.0
    }
}
