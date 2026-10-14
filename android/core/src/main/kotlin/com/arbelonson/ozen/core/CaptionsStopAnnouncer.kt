package com.arbelonson.ozen.core

class CaptionsStopAnnouncer {
    enum class Event { Stopped, Back }

    private var stoppedByFailure = false
    private var pendingStop = false

    fun phaseChanged(phase: PipelinePhase, active: Boolean = true): Event? = when (phase) {
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
                pendingStop = !active
                Event.Stopped
            }
        }
        PipelinePhase.Listening -> {
            pendingStop = false
            if (stoppedByFailure) {
                stoppedByFailure = false
                Event.Back
            } else {
                null
            }
        }
        PipelinePhase.Idle, PipelinePhase.Paused -> {
            stoppedByFailure = false
            pendingStop = false
            null
        }
        PipelinePhase.RequestingMicrophonePermission, is PipelinePhase.PreparingEngine, PipelinePhase.StartingAudio -> null
    }

    fun comingBack(phase: PipelinePhase): Event? {
        val owed = pendingStop && phase is PipelinePhase.Failed
        if (phase is PipelinePhase.Failed) pendingStop = false
        return if (owed) Event.Stopped else null
    }
}
