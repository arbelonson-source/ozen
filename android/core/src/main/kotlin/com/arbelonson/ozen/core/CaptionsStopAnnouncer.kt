package com.arbelonson.ozen.core

class CaptionsStopAnnouncer {
    enum class Event { Stopped, Back }

    private var stoppedByFailure = false

    fun phaseChanged(phase: PipelinePhase): Event? = when (phase) {
        is PipelinePhase.Failed -> {
            if (stoppedByFailure) {
                null
            } else {
                stoppedByFailure = true
                Event.Stopped
            }
        }
        PipelinePhase.Listening -> {
            if (stoppedByFailure) {
                stoppedByFailure = false
                Event.Back
            } else {
                null
            }
        }
        PipelinePhase.Idle, PipelinePhase.Paused -> {
            stoppedByFailure = false
            null
        }
        PipelinePhase.RequestingMicrophonePermission, is PipelinePhase.PreparingEngine, PipelinePhase.StartingAudio -> null
    }
}
