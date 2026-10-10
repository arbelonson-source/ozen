package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class StoppedCaptionsNoticeTest {
    private val glitch = PipelineFailure(PipelineFailure.Kind.TranscriptionStopped, "stream ended")

    private fun engineFailure(kind: EngineUnavailability.Kind) = PipelineFailure(
        PipelineFailure.Kind.EngineUnavailable, "", EngineUnavailability(kind, ""),
    )

    private fun cause(
        phase: PipelinePhase,
        retryScheduled: Boolean = false,
        interrupted: Boolean = false,
        callEnded: Boolean = false,
    ): StoppedCaptionsNotice.Cause? = StoppedCaptionsNotice.cause(
        phase = phase,
        retryScheduled = retryScheduled,
        systemInterrupted = interrupted,
        callEndedDuringInterruption = callEnded,
    )

    private fun failed(failure: PipelineFailure) = StoppedCaptionsNotice.Cause.Failed(failure)

    @Test
    fun `a failure nothing will retry is a stop, one with a retry lined up is not`() {
        assertEquals(failed(glitch), cause(PipelinePhase.Failed(glitch)))
        assertNull(cause(PipelinePhase.Failed(glitch), retryScheduled = true))
    }

    @Test
    fun `running, starting, paused or stopped on purpose is not a stop`() {
        assertNull(cause(PipelinePhase.Listening))
        assertNull(cause(PipelinePhase.StartingAudio))
        assertNull(cause(PipelinePhase.Paused))
        assertNull(cause(PipelinePhase.Idle))
    }

    @Test
    fun `waiting for Wi-Fi starts by itself, so it is not a stop`() {
        assertNull(cause(PipelinePhase.Failed(engineFailure(EngineUnavailability.Kind.WaitingForWiFi))))
        assertEquals(
            failed(engineFailure(EngineUnavailability.Kind.NotEnoughStorage)),
            cause(PipelinePhase.Failed(engineFailure(EngineUnavailability.Kind.NotEnoughStorage))),
        )
    }

    @Test
    fun `during a call nothing is a stop, after it, a microphone that never came back is`() {
        assertNull(cause(PipelinePhase.Listening, interrupted = true))
        assertNull(cause(PipelinePhase.Failed(glitch), interrupted = true))
        assertEquals(StoppedCaptionsNotice.Cause.CallEnded, cause(PipelinePhase.Listening, interrupted = true, callEnded = true))
        assertEquals(StoppedCaptionsNotice.Cause.CallEnded, cause(PipelinePhase.Failed(glitch), interrupted = true, callEnded = true))
        // Paused before the call: nothing was running to stop.
        assertNull(cause(PipelinePhase.Paused, interrupted = true, callEnded = true))
    }

    @Test
    fun `posted once while the phone is put away, never while the app is on screen or switched off`() {
        val notice = StoppedCaptionsNotice()
        assertNull(notice.update(failed(glitch), appIsActive = true, isEnabled = true))
        assertNull(notice.update(failed(glitch), appIsActive = false, isEnabled = false))

        val posted = notice.update(failed(glitch), appIsActive = false, isEnabled = true)
        if (posted !is StoppedCaptionsNotice.Update.Post) fail("expected a notification")
        assertEquals(StoppedCaptionsNotice.IDENTIFIER, posted.content.identifier)
        assertEquals("הכתוביות נעצרו", posted.content.title)
        assertNull(notice.update(failed(glitch), appIsActive = false, isEnabled = true))
        assertNull(notice.update(StoppedCaptionsNotice.Cause.CallEnded, appIsActive = false, isEnabled = true))
    }

    @Test
    fun `once captions run again the notice is withdrawn, and a later stop is told again`() {
        val notice = StoppedCaptionsNotice()
        assertNull(notice.update(null, appIsActive = false, isEnabled = true))
        notice.update(StoppedCaptionsNotice.Cause.CallEnded, appIsActive = false, isEnabled = true)

        assertEquals(
            StoppedCaptionsNotice.Update.Withdraw(StoppedCaptionsNotice.IDENTIFIER),
            notice.update(null, appIsActive = false, isEnabled = true),
        )
        assertNull(notice.update(null, appIsActive = false, isEnabled = true))

        assertIs<StoppedCaptionsNotice.Update.Post>(
            notice.update(failed(glitch), appIsActive = false, isEnabled = true),
            "a new stop after recovering should notify again",
        )
    }

    @Test
    fun `opening the app while captions are still stopped takes the notice away, without posting it again later`() {
        val notice = StoppedCaptionsNotice()
        notice.update(failed(glitch), appIsActive = false, isEnabled = true)
        assertEquals(
            StoppedCaptionsNotice.Update.Withdraw(StoppedCaptionsNotice.IDENTIFIER),
            notice.update(failed(glitch), appIsActive = true, isEnabled = true),
        )
        assertNull(notice.update(failed(glitch), appIsActive = true, isEnabled = true))
        assertNull(notice.update(failed(glitch), appIsActive = false, isEnabled = true))

        // Captions ran again, then stopped again: that's news.
        assertNull(notice.update(null, appIsActive = true, isEnabled = true))
        assertIs<StoppedCaptionsNotice.Update.Post>(
            notice.update(StoppedCaptionsNotice.Cause.CallEnded, appIsActive = false, isEnabled = true),
            "a new stop after recovering should notify again",
        )
    }

    @Test
    fun `the notice breaks through Focus, captions stopping in a pocket is something to know now`() {
        assertTrue(StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.CallEnded).isUrgent)
        assertTrue(StoppedCaptionsNotice.content(failed(glitch)).isUrgent)
        assertEquals("status", StoppedCaptionsNotice.content(failed(glitch)).threadIdentifier)
    }

    @Test
    fun `the message says what she can do about it`() {
        val callEnded = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.CallEnded).body
        val permission = StoppedCaptionsNotice.content(failed(PipelineFailure(PipelineFailure.Kind.MicrophonePermissionDenied, ""))).body
        val speechPermission = StoppedCaptionsNotice.content(failed(engineFailure(EngineUnavailability.Kind.PermissionDenied))).body
        val storage = StoppedCaptionsNotice.content(failed(engineFailure(EngineUnavailability.Kind.NotEnoughStorage))).body
        val noMic = StoppedCaptionsNotice.content(failed(PipelineFailure(PipelineFailure.Kind.NoAudioInputs, ""))).body
        val other = StoppedCaptionsNotice.content(failed(glitch)).body
        val cloudKey = StoppedCaptionsNotice.content(failed(engineFailure(EngineUnavailability.Kind.CloudKeyNeeded))).body
        val cloudCredit = StoppedCaptionsNotice.content(failed(engineFailure(EngineUnavailability.Kind.CloudOutOfCredit))).body
        val offline = StoppedCaptionsNotice.content(failed(engineFailure(EngineUnavailability.Kind.NoInternet))).body
        val computer = StoppedCaptionsNotice.content(failed(engineFailure(EngineUnavailability.Kind.HomeServerUnreachable))).body

        assertTrue(callEnded.contains("אחרי השיחה"))
        assertEquals(speechPermission, permission)
        assertTrue(permission.contains("הרשאה"))
        assertTrue(storage.contains("מקום"))
        assertTrue(noMic.contains("מיקרופון"))
        // Running out of credit is not a key to check: whoever helps her
        // tops up the account instead.
        assertTrue(cloudKey != cloudCredit)
        assertTrue(cloudCredit.contains("התקציב") && !cloudCredit.contains("מפתח"))
        assertTrue(offline.contains("אינטרנט"))
        assertTrue(offline.contains("זיהוי הדיבור שבטלפון") && !offline.contains("Whisper"))
        // Opening the app doesn't wake the computer; captions come back by
        // themselves once it answers.
        assertTrue(computer.contains("אין תשובה מהמחשב"))
        assertEquals(9, setOf(callEnded, permission, storage, noMic, other, cloudKey, cloudCredit, offline, computer).size)
    }

    @Test
    fun `the message is in English when the app is`() {
        Localization.withLanguage(UILanguage.English) {
            val content = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.CallEnded)
            assertEquals("Captions stopped", content.title)
            assertTrue(content.body.contains("After the call"))
            val permission = StoppedCaptionsNotice.content(failed(PipelineFailure(PipelineFailure.Kind.MicrophonePermissionDenied, ""))).body
            assertTrue(permission.contains("permission"))
            val computer = StoppedCaptionsNotice.content(failed(engineFailure(EngineUnavailability.Kind.HomeServerUnreachable))).body
            assertTrue(computer.startsWith("No answer from the computer"))
        }
    }
}

class CaptionPipelinePhaseChangeTest {
    @Test
    fun `each step is reported once, not every bit of download progress`() = runTest {
        val engine = FakeEngine(
            progressUpdates = listOf(
                EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.1),
                EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.5),
                EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.9),
            ),
        )
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { engine },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
            audioWatchdog = AudioStallWatchdog.disabled,
        )
        val reported = ArrayList<String>()
        pipeline.onPhaseChange = { phase ->
            reported.add(
                when (phase) {
                    PipelinePhase.Idle -> "idle"
                    PipelinePhase.RequestingMicrophonePermission -> "permission"
                    is PipelinePhase.PreparingEngine -> "preparing"
                    PipelinePhase.StartingAudio -> "audio"
                    PipelinePhase.Listening -> "listening"
                    PipelinePhase.Paused -> "paused"
                    is PipelinePhase.Failed -> "failed"
                },
            )
        }

        pipeline.start(AppSettings.default)
        delay(100.milliseconds)
        pipeline.pause()

        assertEquals(listOf("permission", "preparing", "audio", "listening", "paused"), reported)
    }
}
