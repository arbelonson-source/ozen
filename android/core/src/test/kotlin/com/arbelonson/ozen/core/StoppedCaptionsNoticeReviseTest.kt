package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class StoppedCaptionsNoticeReviseTest {
    private val glitch = PipelineFailure(PipelineFailure.Kind.TranscriptionStopped, "stream ended")
    private val stalled = PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "other")
    private val noMic = PipelineFailure(PipelineFailure.Kind.NoAudioInputs, "none")

    private fun posted(cause: StoppedCaptionsNotice.Cause): StoppedCaptionsNotice {
        val notice = StoppedCaptionsNotice()
        notice.update(cause, appIsActive = false, isEnabled = true, now = 0.0)
        return notice
    }

    private fun StoppedCaptionsNotice.away(cause: StoppedCaptionsNotice.Cause?, now: Double) =
        update(cause, appIsActive = false, isEnabled = true, now = now)

    @Test
    fun aFailureThatTurnsIntoAnotherThatAsksForTheSameThingQuietlyChangesThePostedWords() {
        val notice = posted(StoppedCaptionsNotice.Cause.Failed(glitch))
        val next = StoppedCaptionsNotice.Cause.Failed(stalled)
        assertEquals(StoppedCaptionsNotice.Update.Revise(StoppedCaptionsNotice.content(next)), notice.away(next, 1.0))
        assertNull(notice.away(next, 2.0))
    }

    @Test
    fun aFailureThatTurnsIntoACallThatDidNotEndWellIsRevisedToo() {
        val notice = posted(StoppedCaptionsNotice.Cause.Failed(glitch))
        assertEquals(
            StoppedCaptionsNotice.Update.Revise(StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.CallEnded)),
            notice.away(StoppedCaptionsNotice.Cause.CallEnded, 1.0),
        )
    }

    @Test
    fun theSameCauseGivesNothing() {
        val notice = posted(StoppedCaptionsNotice.Cause.Failed(glitch))
        assertNull(notice.away(StoppedCaptionsNotice.Cause.Failed(glitch), 1.0))
    }

    @Test
    fun theSameFailureWithAnotherLanguageOfTheRunChangesTheWords() {
        val unavailable = PipelineFailure(
            PipelineFailure.Kind.EngineUnavailable, "",
            EngineUnavailability(EngineUnavailability.Kind.LanguageNotSupportedOnDevice, ""),
        )
        val hebrew = StoppedCaptionsNotice.Cause.Failed(unavailable, TranscriptionEngineKind.AppleSpeech, UILanguage.Hebrew)
        val english = StoppedCaptionsNotice.Cause.Failed(unavailable, TranscriptionEngineKind.AppleSpeech, UILanguage.English)
        val update = posted(hebrew).away(english, 1.0)
        assertNotEquals(StoppedCaptionsNotice.content(hebrew), StoppedCaptionsNotice.content(english))
        assertEquals(StoppedCaptionsNotice.Update.Revise(StoppedCaptionsNotice.content(english)), update)
    }

    @Test
    fun nothingIsRevisedOnceTheAppWasOpened() {
        val notice = posted(StoppedCaptionsNotice.Cause.Failed(glitch))
        val open = notice.update(StoppedCaptionsNotice.Cause.Failed(noMic), appIsActive = true, isEnabled = true, now = 1.0)
        assertIs<StoppedCaptionsNotice.Update.Withdraw>(open)
        assertNull(notice.away(StoppedCaptionsNotice.Cause.CallEnded, 2.0))
    }

    @Test
    fun theDelayedCausesBehaveAsBefore() {
        val notice = posted(StoppedCaptionsNotice.Cause.Failed(glitch))
        val delayed = notice.away(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone, 1.0)
        assertIs<StoppedCaptionsNotice.Update.Replace>(delayed)
        assertIs<StoppedCaptionsNotice.Update.Schedule>(delayed.new)
        assertNull(notice.away(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone, 2.0))
        assertIs<StoppedCaptionsNotice.Update.Post>((notice.away(StoppedCaptionsNotice.Cause.Failed(glitch), 3.0) as StoppedCaptionsNotice.Update.Replace).new)
    }
}
