package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionsHandoverTest {
    private val notRunning = listOf(PipelinePhase.Paused, PipelinePhase.Idle, PipelinePhase.StartingAudio, PipelinePhase.RequestingMicrophonePermission)

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
}
