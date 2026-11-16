package com.arbelonson.ozen

import com.arbelonson.ozen.core.PipelinePhase

object ServiceKeepAlive {
    fun ends(phase: PipelinePhase, pausedForSpeech: Boolean, soundsOnly: Boolean, pausedForRecording: Boolean = false, startsAgainAfterRecording: Boolean = false): Boolean =
        !soundsOnly && !startsAgainAfterRecording && (phase == PipelinePhase.Idle || (phase == PipelinePhase.Paused && !pausedForSpeech && !pausedForRecording))
}
