package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionsHandoverTest {
    private val notRunning = listOf(PipelinePhase.Paused, PipelinePhase.Idle)
    private val loading = listOf(PipelinePhase.StartingAudio, PipelinePhase.RequestingMicrophonePermission, PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel)))
    private val failed = PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "x"))

    @Test
    fun runningCaptionsArePausedAndBroughtBack() {
        val handover = CaptionsHandover()
        assertTrue(handover.begin(PipelinePhase.Listening))
        assertTrue(handover.pausedHere)
        assertTrue(handover.end(PipelinePhase.Paused))
        assertFalse(handover.pausedHere)
    }

    @Test
    fun captionsThatWereNotRunningAreLeftAlone() {
        for (phase in notRunning) {
            val handover = CaptionsHandover()
            assertFalse(handover.begin(phase), "$phase")
            assertFalse(handover.end(phase), "$phase")
        }
    }

    @Test
    fun captionsBroughtBackOrStoppedInBetweenAreNotStartedAgain() {
        for (later in listOf(PipelinePhase.Listening, PipelinePhase.Idle)) {
            val handover = CaptionsHandover()
            handover.begin(PipelinePhase.Listening)
            assertFalse(handover.end(later), "$later")
        }
    }

    @Test
    fun givingBackTwiceResumesOnlyOnce() {
        val handover = CaptionsHandover()
        handover.begin(PipelinePhase.Listening)
        assertTrue(handover.end(PipelinePhase.Paused))
        assertFalse(handover.end(PipelinePhase.Paused))
    }

    @Test
    fun aSecondBeginWhilePausedHereDoesNothing() {
        val handover = CaptionsHandover()
        assertTrue(handover.begin(PipelinePhase.Listening))
        assertFalse(handover.begin(PipelinePhase.Listening))
    }

    @Test
    fun captionsTheUserTookOverAreNotGivenBack() {
        val handover = CaptionsHandover()
        handover.begin(PipelinePhase.Listening)
        assertTrue(handover.cancel())
        assertFalse(handover.pausedHere)
        assertFalse(handover.end(PipelinePhase.Paused))
        assertFalse(handover.cancel())
    }

    @Test
    fun aStartStillLoadingIsStoppedAndStartedAgain() {
        for (phase in loading) {
            val handover = CaptionsHandover()
            assertTrue(handover.begin(phase), "$phase")
            assertTrue(handover.pausedHere)
            assertTrue(handover.end(PipelinePhase.Idle), "$phase")
            assertFalse(handover.pausedHere)
        }
    }

    @Test
    fun aStartWaitingForARetryIsStoppedAndStartedAgain() {
        val handover = CaptionsHandover()
        assertTrue(handover.begin(failed, waitingForRetry = true))
        assertTrue(handover.end(PipelinePhase.Idle))
    }

    @Test
    fun aFailureNothingRetriesIsLeftAlone() {
        val handover = CaptionsHandover()
        assertFalse(handover.begin(failed, waitingForRetry = false))
        assertFalse(handover.end(PipelinePhase.Idle))
    }

    @Test
    fun aHandedOverStartIsNotStartedAgainOncePeopleStartedOrPausedItThemselves() {
        for (later in listOf(PipelinePhase.Listening, PipelinePhase.Paused, PipelinePhase.StartingAudio)) {
            val handover = CaptionsHandover()
            handover.begin(PipelinePhase.StartingAudio)
            assertFalse(handover.end(later), "$later")
        }
    }

    @Test
    fun aStartTheUserTookOverIsNotGivenBack() {
        val handover = CaptionsHandover()
        handover.begin(PipelinePhase.StartingAudio)
        assertTrue(handover.cancel())
        assertFalse(handover.end(PipelinePhase.Idle))
    }

    @Test
    fun runningCaptionsStillComeBackOnlyFromPaused() {
        val handover = CaptionsHandover()
        handover.begin(PipelinePhase.Listening)
        assertFalse(handover.end(PipelinePhase.Idle))
    }
}
