package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptionsStopAnnouncerTest {
    private val stalled = PipelinePhase.Failed(PipelineFailure(kind = PipelineFailure.Kind.AudioSessionFailed, detail = "stalled"))
    private val preparing = PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.LoadingModel))

    @Test
    fun `a failure is announced once through its retries, and the return to listening after it`() {
        val announcer = CaptionsStopAnnouncer()
        val phases = listOf(
            PipelinePhase.StartingAudio, PipelinePhase.Listening,
            stalled, preparing,
            PipelinePhase.Failed(PipelineFailure(kind = PipelineFailure.Kind.NoAudioInputs, detail = "")),
            PipelinePhase.StartingAudio, PipelinePhase.Listening, PipelinePhase.Listening,
        )
        val events = phases.map { announcer.phaseChanged(it) }
        val stopped = CaptionsStopAnnouncer.Event.Stopped
        val back = CaptionsStopAnnouncer.Event.Back
        assertEquals(listOf(null, null, stopped, null, null, null, back, null), events)
    }

    @Test
    fun `starting, pausing and stopping on purpose announce nothing`() {
        val announcer = CaptionsStopAnnouncer()
        val phases = listOf(
            PipelinePhase.RequestingMicrophonePermission, preparing, PipelinePhase.StartingAudio,
            PipelinePhase.Listening, PipelinePhase.Paused, PipelinePhase.Listening, PipelinePhase.Idle,
        )
        assertTrue(phases.map { announcer.phaseChanged(it) }.all { it == null })
    }

    @Test
    fun `stopped by hand after a failure, starting again later isn't a recovery`() {
        val announcer = CaptionsStopAnnouncer()
        assertEquals(CaptionsStopAnnouncer.Event.Stopped, announcer.phaseChanged(stalled))
        assertNull(announcer.phaseChanged(PipelinePhase.Idle))
        assertNull(announcer.phaseChanged(PipelinePhase.Listening))
        assertEquals(CaptionsStopAnnouncer.Event.Stopped, announcer.phaseChanged(stalled))
    }
}
