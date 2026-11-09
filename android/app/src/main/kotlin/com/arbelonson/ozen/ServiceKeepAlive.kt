package com.arbelonson.ozen

import com.arbelonson.ozen.core.PipelinePhase

object ServiceKeepAlive {
    fun ends(phase: PipelinePhase, pausedForSpeech: Boolean, soundsOnly: Boolean): Boolean =
        !soundsOnly && (phase == PipelinePhase.Idle || (phase == PipelinePhase.Paused && !pausedForSpeech))
}
