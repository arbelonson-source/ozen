package com.arbelonson.ozen

import com.arbelonson.ozen.core.CaptionsStopAnnouncer
import com.arbelonson.ozen.core.PipelinePhase

class CaptionPhaseChanges(
    private val replies: SpokenReplies,
    private val stopAnnouncer: CaptionsStopAnnouncer,
    private val appIsActive: () -> Boolean,
    private val seconds: () -> Double,
    private val changed: (PipelinePhase) -> Unit,
    private val publish: () -> Unit,
    private val say: (CaptionsStopAnnouncer.Event, PipelinePhase) -> Unit,
) {
    fun phaseChanged(phase: PipelinePhase) {
        changed(phase)
        stopAnnouncer.phaseChanged(phase, appIsActive(), seconds())?.let { say(it, phase) }
        replies.captionsCameOn()
        publish()
    }
}
