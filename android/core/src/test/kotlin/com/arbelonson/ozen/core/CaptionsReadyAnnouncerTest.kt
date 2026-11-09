package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptionsReadyAnnouncerTest {
    private val stalled = PipelinePhase.Failed(PipelineFailure(kind = PipelineFailure.Kind.AudioSessionFailed, detail = "stalled"))
    private val preparing = PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.LoadingModel))

    @Test
    fun `listening after a preparation of 12 seconds is announced as ready`() {
        val announcer = CaptionsStopAnnouncer()
        assertNull(announcer.phaseChanged(preparing, seconds = 0.0))
        assertNull(announcer.phaseChanged(PipelinePhase.StartingAudio, seconds = 11.0))
        assertEquals(CaptionsStopAnnouncer.Event.Ready, announcer.phaseChanged(PipelinePhase.Listening, seconds = 12.0))
        assertNull(announcer.phaseChanged(PipelinePhase.Listening, seconds = 13.0))
    }

    @Test
    fun `a preparation of 3 seconds says nothing`() {
        val announcer = CaptionsStopAnnouncer()
        assertNull(announcer.phaseChanged(preparing, seconds = 0.0))
        assertNull(announcer.phaseChanged(PipelinePhase.Listening, seconds = 3.0))
    }

    @Test
    fun `listening after a failure is back, never ready`() {
        val announcer = CaptionsStopAnnouncer()
        assertNull(announcer.phaseChanged(preparing, seconds = 0.0))
        assertEquals(CaptionsStopAnnouncer.Event.Stopped, announcer.phaseChanged(stalled, seconds = 20.0))
        assertNull(announcer.phaseChanged(preparing, seconds = 21.0))
        assertEquals(CaptionsStopAnnouncer.Event.Back, announcer.phaseChanged(PipelinePhase.Listening, seconds = 60.0))
    }

    @Test
    fun `pausing and resuming quickly says nothing`() {
        val announcer = CaptionsStopAnnouncer()
        assertNull(announcer.phaseChanged(PipelinePhase.Listening, seconds = 0.0))
        assertNull(announcer.phaseChanged(PipelinePhase.Paused, seconds = 100.0))
        assertNull(announcer.phaseChanged(PipelinePhase.StartingAudio, seconds = 200.0))
        assertNull(announcer.phaseChanged(PipelinePhase.Listening, seconds = 201.0))
    }

    @Test
    fun `a second long preparation in a later start is announced again`() {
        val ready = CaptionsStopAnnouncer.Event.Ready
        val announcer = CaptionsStopAnnouncer()
        assertNull(announcer.phaseChanged(preparing, seconds = 0.0))
        assertEquals(ready, announcer.phaseChanged(PipelinePhase.Listening, seconds = 15.0))
        assertNull(announcer.phaseChanged(PipelinePhase.Idle, seconds = 100.0))
        assertNull(announcer.phaseChanged(preparing, seconds = 200.0))
        assertEquals(ready, announcer.phaseChanged(PipelinePhase.Listening, seconds = 230.0))
    }

    @Test
    fun `stopping in the middle of a preparation resets the wait`() {
        val announcer = CaptionsStopAnnouncer()
        assertNull(announcer.phaseChanged(preparing, seconds = 0.0))
        assertNull(announcer.phaseChanged(PipelinePhase.Idle, seconds = 5.0))
        assertNull(announcer.phaseChanged(PipelinePhase.StartingAudio, seconds = 50.0))
        assertNull(announcer.phaseChanged(PipelinePhase.Listening, seconds = 52.0))
    }

    @Test
    fun `ready reached while away is told once on return, and not after a pause there or when said in front`() {
        val ready = CaptionsStopAnnouncer.Event.Ready
        val away = CaptionsStopAnnouncer()
        away.phaseChanged(preparing, active = false, seconds = 0.0)
        away.phaseChanged(PipelinePhase.Listening, active = false, seconds = 60.0)
        assertEquals(ready, away.comingBack(PipelinePhase.Listening))
        assertNull(away.comingBack(PipelinePhase.Listening))

        val paused = CaptionsStopAnnouncer()
        paused.phaseChanged(preparing, active = false, seconds = 0.0)
        paused.phaseChanged(PipelinePhase.Listening, active = false, seconds = 60.0)
        paused.phaseChanged(PipelinePhase.Paused, active = false, seconds = 70.0)
        paused.phaseChanged(PipelinePhase.StartingAudio, active = false, seconds = 80.0)
        paused.phaseChanged(PipelinePhase.Listening, active = false, seconds = 81.0)
        assertNull(paused.comingBack(PipelinePhase.Listening))

        val front = CaptionsStopAnnouncer()
        front.phaseChanged(preparing, active = true, seconds = 0.0)
        assertEquals(ready, front.phaseChanged(PipelinePhase.Listening, active = true, seconds = 12.0))
        assertNull(front.comingBack(PipelinePhase.Listening))
    }

    @Test
    fun `a ready owed through a failure that healed away is told on return, but not once captions were heard coming back`() {
        val back = CaptionsStopAnnouncer.Event.Back
        val healedAway = CaptionsStopAnnouncer()
        healedAway.phaseChanged(preparing, active = false, seconds = 0.0)
        healedAway.phaseChanged(PipelinePhase.Listening, active = false, seconds = 60.0)
        healedAway.phaseChanged(stalled, active = false, seconds = 70.0)
        healedAway.phaseChanged(preparing, active = false, seconds = 71.0)
        assertEquals(back, healedAway.phaseChanged(PipelinePhase.Listening, active = false, seconds = 72.0))
        assertEquals(CaptionsStopAnnouncer.Event.Ready, healedAway.comingBack(PipelinePhase.Listening))

        val backInFront = CaptionsStopAnnouncer()
        backInFront.phaseChanged(preparing, active = false, seconds = 0.0)
        backInFront.phaseChanged(PipelinePhase.Listening, active = false, seconds = 60.0)
        backInFront.phaseChanged(stalled, active = false, seconds = 70.0)
        backInFront.phaseChanged(preparing, active = false, seconds = 71.0)
        assertNull(backInFront.comingBack(preparing))
        assertEquals(back, backInFront.phaseChanged(PipelinePhase.Listening, active = true, seconds = 72.0))
        assertNull(backInFront.comingBack(PipelinePhase.Listening))
    }
}
