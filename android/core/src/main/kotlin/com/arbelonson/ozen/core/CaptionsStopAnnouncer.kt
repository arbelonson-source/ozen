package com.arbelonson.ozen.core

class CaptionsStopAnnouncer {
    enum class Event { Stopped, Back, Ready, DownloadStarted }

    private var waitingForWiFi = false
    private var stoppedByFailure = false
    private var startBegan: Double? = null
    private var pendingStop = false
    private var pendingReady = false

    fun phaseChanged(phase: PipelinePhase, active: Boolean = true, seconds: Double = 0.0): Event? = when (phase) {
        is PipelinePhase.Failed -> {
            waitingForWiFi = phase.reason.engineUnavailability?.kind == EngineUnavailability.Kind.WaitingForWiFi
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
            val event = if (stoppedByFailure) {
                stoppedByFailure = false
                Event.Back
            } else if (began != null && seconds - began >= LONG_PREPARATION_SECONDS) {
                Event.Ready
            } else {
                null
            }
            if (active) pendingReady = false else if (event == Event.Ready) pendingReady = true
            event
        }
        PipelinePhase.Idle, PipelinePhase.Paused -> {
            stoppedByFailure = false
            pendingStop = false
            pendingReady = false
            startBegan = null
            null
        }
        PipelinePhase.RequestingMicrophonePermission, is PipelinePhase.PreparingEngine, PipelinePhase.StartingAudio -> {
            if (startBegan == null) startBegan = seconds
            if (stoppedByFailure && waitingForWiFi && phase.preparationProgress?.stage == EnginePreparationProgress.Stage.DownloadingModel) {
                waitingForWiFi = false
                stoppedByFailure = false
                Event.DownloadStarted
            } else {
                null
            }
        }
    }

    fun comingBack(phase: PipelinePhase): Event? = when {
        phase is PipelinePhase.Failed -> {
            val owed = pendingStop
            pendingStop = false
            if (owed) Event.Stopped else null
        }
        phase == PipelinePhase.Listening && pendingReady -> {
            pendingReady = false
            Event.Ready
        }
        else -> null
    }

    companion object {
        const val LONG_PREPARATION_SECONDS = 10.0
    }
}
