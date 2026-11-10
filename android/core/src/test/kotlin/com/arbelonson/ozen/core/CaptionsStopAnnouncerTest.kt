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

    @Test
    fun `a stop while away is told once on return, and not when it healed or happened in front`() {
        val stopped = CaptionsStopAnnouncer.Event.Stopped
        val away = CaptionsStopAnnouncer()
        assertEquals(stopped, away.phaseChanged(stalled, active = false))
        assertEquals(stopped, away.comingBack(stalled))
        assertNull(away.comingBack(stalled))

        val healed = CaptionsStopAnnouncer()
        healed.phaseChanged(stalled, active = false)
        healed.phaseChanged(PipelinePhase.Listening, active = false)
        assertNull(healed.comingBack(PipelinePhase.Listening))

        val front = CaptionsStopAnnouncer()
        front.phaseChanged(stalled, active = true)
        assertNull(front.comingBack(stalled))
    }

    @Test
    fun `back in front while captions restart, a second failure there is still told`() {
        val stopped = CaptionsStopAnnouncer.Event.Stopped
        val announcer = CaptionsStopAnnouncer()
        assertEquals(stopped, announcer.phaseChanged(stalled, active = false))
        assertNull(announcer.phaseChanged(preparing, active = false))
        assertNull(announcer.comingBack(preparing))
        assertEquals(stopped, announcer.phaseChanged(stalled, active = true))
        assertNull(announcer.phaseChanged(stalled, active = true))
    }

    private val waitingForWiFi = PipelinePhase.Failed(
        PipelineFailure(
            kind = PipelineFailure.Kind.EngineUnavailable,
            detail = "626 MB to download, waiting for Wi-Fi",
            engineUnavailability = EngineUnavailability(EngineUnavailability.Kind.WaitingForWiFi, "cellular", 626),
        ),
    )
    private val checking = PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.CheckingSupport))
    private val downloading = PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.0))

    @Test
    fun `the download that was waiting for Wi-Fi is told when it starts, and a failure after it is told again`() {
        val announcer = CaptionsStopAnnouncer()
        assertEquals(CaptionsStopAnnouncer.Event.Stopped, announcer.phaseChanged(waitingForWiFi))
        assertNull(announcer.phaseChanged(PipelinePhase.RequestingMicrophonePermission))
        assertNull(announcer.phaseChanged(checking))
        assertEquals(CaptionsStopAnnouncer.Event.DownloadStarted, announcer.phaseChanged(downloading))
        assertNull(announcer.phaseChanged(downloading.copy(progress = downloading.progress.copy(fraction = 0.4))))
        val lost = PipelinePhase.Failed(PipelineFailure(kind = PipelineFailure.Kind.EngineUnavailable, detail = "offline"))
        assertEquals(CaptionsStopAnnouncer.Event.Stopped, announcer.phaseChanged(lost))
    }

    @Test
    fun `a retry still waiting for Wi-Fi, other retries and a start by hand say nothing about a download`() {
        val stillWaiting = CaptionsStopAnnouncer()
        stillWaiting.phaseChanged(waitingForWiFi)
        val again = listOf(PipelinePhase.RequestingMicrophonePermission, checking, waitingForWiFi)
        assertTrue(again.map { stillWaiting.phaseChanged(it) }.all { it == null })

        val stalledRetry = CaptionsStopAnnouncer()
        stalledRetry.phaseChanged(stalled)
        assertNull(stalledRetry.phaseChanged(preparing))
        assertNull(stalledRetry.phaseChanged(downloading))

        val byHand = CaptionsStopAnnouncer()
        byHand.phaseChanged(waitingForWiFi)
        byHand.phaseChanged(PipelinePhase.Idle)
        assertNull(byHand.phaseChanged(PipelinePhase.RequestingMicrophonePermission))
        assertNull(byHand.phaseChanged(downloading))
    }

    @Test
    fun `leaving again while captions restart and fail keeps the stop owed for the return`() {
        val stopped = CaptionsStopAnnouncer.Event.Stopped
        val announcer = CaptionsStopAnnouncer()
        announcer.phaseChanged(stalled, active = false)
        announcer.phaseChanged(preparing, active = false)
        assertNull(announcer.phaseChanged(stalled, active = false))
        assertEquals(stopped, announcer.comingBack(stalled))
    }
}
