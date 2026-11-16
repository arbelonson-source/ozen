package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StoppedCaptionsNoticeRealertTest {
    private val glitch = StoppedCaptionsNotice.Cause.Failed(PipelineFailure(PipelineFailure.Kind.TranscriptionStopped, "stream ended"))
    private val other = StoppedCaptionsNotice.Cause.Failed(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "other"))
    private val noMic = StoppedCaptionsNotice.Cause.Failed(PipelineFailure(PipelineFailure.Kind.NoAudioInputs, "none"))
    private val denied = StoppedCaptionsNotice.Cause.Failed(PipelineFailure(PipelineFailure.Kind.MicrophonePermissionDenied, "denied"))

    private fun posted(cause: StoppedCaptionsNotice.Cause): StoppedCaptionsNotice {
        val notice = StoppedCaptionsNotice()
        notice.update(cause, appIsActive = false, isEnabled = true, now = 0.0)
        return notice
    }

    private fun StoppedCaptionsNotice.next(cause: StoppedCaptionsNotice.Cause?, now: Double) =
        update(cause, appIsActive = false, isEnabled = true, now = now)

    private fun realert(cause: StoppedCaptionsNotice.Cause) = StoppedCaptionsNotice.Update.Realert(StoppedCaptionsNotice.content(cause))

    private fun revise(cause: StoppedCaptionsNotice.Cause) = StoppedCaptionsNotice.Update.Revise(StoppedCaptionsNotice.content(cause))

    @Test
    fun tapToContinueTurningIntoNoMicrophoneAlertsAgain() {
        assertEquals(realert(noMic), posted(glitch).next(noMic, 1.0))
    }

    @Test
    fun aDetailOnlyChangeStaysQuiet() {
        val notice = posted(glitch)
        assertEquals(revise(StoppedCaptionsNotice.Cause.CallEnded), notice.next(StoppedCaptionsNotice.Cause.CallEnded, 1.0))
        assertEquals(revise(other), notice.next(other, 2.0))
    }

    @Test
    fun aFlappingCauseAlertsAtMostOncePerStop() {
        val notice = posted(glitch)
        assertEquals(realert(noMic), notice.next(noMic, 1.0))
        assertEquals(revise(glitch), notice.next(glitch, 2.0))
        assertEquals(revise(denied), notice.next(denied, 3.0))
    }

    @Test
    fun aNewStopAfterCaptionsRanAgainMayAlertOnceMore() {
        val notice = posted(glitch)
        notice.next(noMic, 1.0)
        notice.next(null, 2.0)
        notice.next(glitch, 3.0)
        assertEquals(realert(denied), notice.next(denied, 4.0))
    }

    @Test
    fun aDeliveredSoundAlertsNoticeAlertsAgainWhenTheMicrophoneIsSilent() {
        val notice = StoppedCaptionsNotice()
        notice.next(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking, 0.0)
        val silent = StoppedCaptionsNotice.Cause.SoundAlertsMicrophoneSilent
        assertEquals(realert(silent), notice.next(silent, 100.0))
        assertEquals(revise(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking), notice.next(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking, 110.0))
    }

    @Test
    fun eachCauseNamesWhatToDo() {
        val action = { cause: StoppedCaptionsNotice.Cause -> StoppedCaptionsNotice.action(cause) }
        assertEquals(NoticeAction.OpenToContinue, action(glitch))
        assertEquals(NoticeAction.OpenToContinue, action(StoppedCaptionsNotice.Cause.CallEnded))
        assertEquals(NoticeAction.CheckMicrophone, action(noMic))
        assertEquals(NoticeAction.AllowMicrophone, action(denied))
        assertEquals(NoticeAction.NothingToDo, action(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking))
        assertEquals(NoticeAction.TurnSoundAlertsOn, action(StoppedCaptionsNotice.Cause.SoundAlertsStopped))
    }

    @Test
    fun aCloudTimeoutIsNotALostInternetConnection() {
        val slow = StoppedCaptionsNotice.Cause.Failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", CloudSpeechError.TimedOut.unavailability))
        val offline = StoppedCaptionsNotice.Cause.Failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", CloudSpeechError.Offline.unavailability))
        assertEquals(NoticeAction.OpenToContinue, StoppedCaptionsNotice.action(slow))
        assertEquals(NoticeAction.CheckConnection, StoppedCaptionsNotice.action(offline))
        assertEquals(realert(offline), posted(slow).next(offline, 1.0))
    }
}
