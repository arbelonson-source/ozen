package com.arbelonson.ozen

import com.arbelonson.ozen.core.PipelinePhase
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceKeepAliveTest {
    @Test
    fun theServiceEndsOnIdleOrPausedAsBeforeButStaysWhileOnlySoundsAreListenedFor() {
        assertEquals(true, ServiceKeepAlive.ends(PipelinePhase.Idle, pausedForSpeech = false, soundsOnly = false))
        assertEquals(true, ServiceKeepAlive.ends(PipelinePhase.Paused, pausedForSpeech = false, soundsOnly = false))
        assertEquals(false, ServiceKeepAlive.ends(PipelinePhase.Paused, pausedForSpeech = true, soundsOnly = false))
        assertEquals(false, ServiceKeepAlive.ends(PipelinePhase.Listening, pausedForSpeech = false, soundsOnly = false))
        assertEquals(false, ServiceKeepAlive.ends(PipelinePhase.Idle, pausedForSpeech = false, soundsOnly = true))
        assertEquals(false, ServiceKeepAlive.ends(PipelinePhase.Paused, pausedForSpeech = false, soundsOnly = true))
    }
}
