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
        byCall: Boolean = false,
        callEnded: Boolean = false,
        soundsOnly: Boolean = false,
        soundAlertsNotWorking: Boolean = false,
    ): StoppedCaptionsNotice.Cause? = StoppedCaptionsNotice.cause(
        phase = phase,
        retryScheduled = retryScheduled,
        systemInterrupted = interrupted,
        callEndedDuringInterruption = callEnded,
        interruptedByCall = byCall,
        soundsOnlyChosen = soundsOnly,
        soundAlertsNotWorking = soundAlertsNotWorking,
    )

    private fun failed(failure: PipelineFailure) = StoppedCaptionsNotice.Cause.Failed(failure)

    @Test
    fun `a failure nothing will retry is a stop, one with a retry lined up is not`() {
        assertEquals(failed(glitch), cause(PipelinePhase.Failed(glitch)))
        assertNull(cause(PipelinePhase.Failed(glitch), retryScheduled = true))
    }

    @Test
    fun `sounds alone whose microphone never came back are a stop, told as sound alerts stopped`() = Localization.withLanguage(UILanguage.English) {
        val stopped = StoppedCaptionsNotice.cause(PipelinePhase.Paused, retryScheduled = false, systemInterrupted = false, callEndedDuringInterruption = false, soundsOnlyStopped = true)
        assertEquals(StoppedCaptionsNotice.Cause.SoundAlertsStopped, stopped)
        assertEquals(StoppedCaptionsNotice.Cause.SoundAlertsStopped, StoppedCaptionsNotice.cause(PipelinePhase.Idle, false, false, false, soundsOnlyStopped = true))
        val content = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.SoundAlertsStopped)
        assertEquals("Sound alerts stopped", content.title)
        assertEquals("The microphone stopped and didn’t come back. Open Ozen to turn sound alerts on again.", content.body)
        assertEquals("Captions stopped", StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.EndedByPhone).title)
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
        assertNull(cause(PipelinePhase.Listening, interrupted = true, byCall = true))
        assertNull(cause(PipelinePhase.Failed(glitch), interrupted = true, byCall = true))
        assertEquals(StoppedCaptionsNotice.Cause.CallEnded, cause(PipelinePhase.Listening, interrupted = true, byCall = true, callEnded = true))
        assertEquals(StoppedCaptionsNotice.Cause.CallEnded, cause(PipelinePhase.Failed(glitch), interrupted = true, byCall = true, callEnded = true))
        // Paused before the call: nothing was running to stop.
        assertNull(cause(PipelinePhase.Paused, interrupted = true, byCall = true, callEnded = true))
    }

    @Test
    fun `another app keeping the microphone is a stop to tell about, unless a call is why or captions were paused`() {
        assertEquals(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone, cause(PipelinePhase.Listening, interrupted = true))
        assertEquals(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone, cause(PipelinePhase.Failed(glitch), interrupted = true))
        assertNull(cause(PipelinePhase.Paused, interrupted = true))
        assertNull(cause(PipelinePhase.Idle, interrupted = true))
        assertNull(cause(PipelinePhase.Listening, interrupted = true, byCall = true))
        assertNull(cause(PipelinePhase.Listening, interrupted = false))
    }

    @Test
    fun `sound alerts alone, which leave the phase idle or paused, are told about when another app or a stuck call has the microphone`() {
        assertEquals(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone, cause(PipelinePhase.Idle, interrupted = true, soundsOnly = true))
        assertEquals(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone, cause(PipelinePhase.Paused, interrupted = true, soundsOnly = true))
        assertNull(cause(PipelinePhase.Idle, interrupted = true, byCall = true, soundsOnly = true))
        assertEquals(StoppedCaptionsNotice.Cause.CallEnded, cause(PipelinePhase.Idle, interrupted = true, byCall = true, callEnded = true, soundsOnly = true))
        assertNull(cause(PipelinePhase.Idle, interrupted = true))
    }

    @Test
    fun `the other-app notice goes out after a minute, once, and is cancelled when the app opens or the microphone is back`() {
        val notice = StoppedCaptionsNotice()
        val id = StoppedCaptionsNotice.IDENTIFIER
        val other = StoppedCaptionsNotice.Cause.OtherAppHasMicrophone
        assertEquals(60.0, StoppedCaptionsNotice.OTHER_APP_DELAY_SECONDS)
        assertNull(notice.update(other, appIsActive = false, isEnabled = false))

        val scheduled = notice.update(other, appIsActive = false, isEnabled = true)
        if (scheduled !is StoppedCaptionsNotice.Update.Schedule) fail("expected a notification to schedule")
        assertEquals(60.0, scheduled.afterSeconds)
        assertEquals(id, scheduled.content.identifier)
        assertTrue(scheduled.content.isUrgent)
        assertNull(notice.update(other, appIsActive = false, isEnabled = true))

        assertEquals(StoppedCaptionsNotice.Update.Cancel(id), notice.update(other, appIsActive = true, isEnabled = true))
        assertNull(notice.update(other, appIsActive = false, isEnabled = true))

        assertNull(notice.update(null, appIsActive = false, isEnabled = true))
        assertIs<StoppedCaptionsNotice.Update.Schedule>(
            notice.update(other, appIsActive = false, isEnabled = true),
            "a new interruption should be told again",
        )
        assertEquals(StoppedCaptionsNotice.Update.Cancel(id), notice.update(null, appIsActive = false, isEnabled = true))
    }

    @Test
    fun `a scheduled sound-alerts notice is dropped and the failure is posted at once with its own wording`() {
        val notice = StoppedCaptionsNotice()
        val id = StoppedCaptionsNotice.IDENTIFIER
        assertIs<StoppedCaptionsNotice.Update.Schedule>(
            notice.update(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking, appIsActive = false, isEnabled = true),
        )
        val replaced = notice.update(failed(glitch), appIsActive = false, isEnabled = true)
        if (replaced !is StoppedCaptionsNotice.Update.Replace) fail("expected a cancel plus a post")
        assertEquals(StoppedCaptionsNotice.Update.Cancel(id), replaced.old)
        val post = replaced.new
        if (post !is StoppedCaptionsNotice.Update.Post) fail("expected a post")
        assertEquals("הכתוביות נעצרו", post.content.title)
        assertNull(notice.update(failed(engineFailure(EngineUnavailability.Kind.NoInternet)), appIsActive = false, isEnabled = true))
        assertEquals(StoppedCaptionsNotice.Update.Withdraw(id), notice.update(null, appIsActive = false, isEnabled = true))
    }

    @Test
    fun `a scheduled other-app notice is dropped and sound alerts are scheduled in its place`() {
        val notice = StoppedCaptionsNotice()
        val soundAlerts = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        notice.update(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone, appIsActive = false, isEnabled = true)
        val replaced = notice.update(soundAlerts, appIsActive = false, isEnabled = true)
        if (replaced !is StoppedCaptionsNotice.Update.Replace) fail("expected a cancel plus a schedule")
        assertEquals(StoppedCaptionsNotice.Update.Cancel(StoppedCaptionsNotice.IDENTIFIER), replaced.old)
        val schedule = replaced.new
        if (schedule !is StoppedCaptionsNotice.Update.Schedule) fail("expected a schedule")
        assertEquals(StoppedCaptionsNotice.SOUND_ALERTS_DELAY_SECONDS, schedule.afterSeconds)
        assertEquals(StoppedCaptionsNotice.soundAlertsNotWorkingTitle(), schedule.content.title)
        assertNull(notice.update(soundAlerts, appIsActive = false, isEnabled = true))
    }

    @Test
    fun `a posted failure is withdrawn when the cause becomes another app, which is then scheduled`() {
        val notice = StoppedCaptionsNotice()
        notice.update(failed(glitch), appIsActive = false, isEnabled = true)
        val replaced = notice.update(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone, appIsActive = false, isEnabled = true)
        if (replaced !is StoppedCaptionsNotice.Update.Replace) fail("expected a withdraw plus a schedule")
        assertEquals(StoppedCaptionsNotice.Update.Withdraw(StoppedCaptionsNotice.IDENTIFIER), replaced.old)
        val schedule = replaced.new
        if (schedule !is StoppedCaptionsNotice.Update.Schedule) fail("expected a schedule")
        assertEquals(StoppedCaptionsNotice.OTHER_APP_DELAY_SECONDS, schedule.afterSeconds)
    }

    @Test
    fun `a new cause seen while the app is open is told once the phone is put away`() {
        val notice = StoppedCaptionsNotice()
        val soundAlerts = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        notice.update(soundAlerts, appIsActive = false, isEnabled = true)
        assertEquals(StoppedCaptionsNotice.Update.Cancel(StoppedCaptionsNotice.IDENTIFIER), notice.update(soundAlerts, appIsActive = true, isEnabled = true))
        assertNull(notice.update(soundAlerts, appIsActive = false, isEnabled = true))
        assertNull(notice.update(failed(glitch), appIsActive = true, isEnabled = true))
        assertIs<StoppedCaptionsNotice.Update.Post>(notice.update(failed(glitch), appIsActive = false, isEnabled = true))
    }

    private fun scheduledDelay(update: StoppedCaptionsNotice.Update?): Double? = (update as? StoppedCaptionsNotice.Update.Schedule)?.afterSeconds

    @Test
    fun `sound alerts that fail 50 seconds, work 20, and fail again are still told because the minute counts across the gaps`() {
        val notice = StoppedCaptionsNotice()
        val down = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        assertEquals(60.0, scheduledDelay(notice.update(down, appIsActive = false, isEnabled = true, now = 0.0)))
        assertEquals(StoppedCaptionsNotice.Update.Cancel(StoppedCaptionsNotice.IDENTIFIER), notice.update(null, appIsActive = false, isEnabled = true, now = 50.0))
        assertEquals(10.0, scheduledDelay(notice.update(down, appIsActive = false, isEnabled = true, now = 70.0)))
        notice.update(null, appIsActive = false, isEnabled = true, now = 75.0)
        assertEquals(StoppedCaptionsNotice.SOUND_ALERTS_MINIMUM_DELAY_SECONDS, scheduledDelay(notice.update(down, appIsActive = false, isEnabled = true, now = 95.0)))
    }

    @Test
    fun `an hour of sound alerts failing 50 seconds and working 20 delivers one notice, not one per cycle`() {
        val notice = StoppedCaptionsNotice()
        var schedules = 0
        var withdrawals = 0
        var now = 0.0
        while (now < 3600.0) {
            for ((cause, length) in listOf(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking to 50.0, null to 20.0)) {
                when (notice.update(cause, appIsActive = false, isEnabled = true, now = now)) {
                    is StoppedCaptionsNotice.Update.Schedule, is StoppedCaptionsNotice.Update.Post, is StoppedCaptionsNotice.Update.Replace -> schedules++
                    is StoppedCaptionsNotice.Update.Cancel, is StoppedCaptionsNotice.Update.Withdraw -> withdrawals++
                    null -> Unit
                }
                now += length
            }
        }
        assertEquals(2, schedules) // the first 50 s stretch is under a minute and is dropped; the second one delivers
        assertEquals(1, withdrawals)
        assertEquals(
            StoppedCaptionsNotice.Update.Cancel(StoppedCaptionsNotice.IDENTIFIER),
            notice.update(null, appIsActive = false, isEnabled = true, now = now + StoppedCaptionsNotice.SOUND_ALERTS_WINDOW_SECONDS + 1),
        )
    }

    @Test
    fun `one short blip of sound alerts failing does not bring the notice forward and a long gap forgets old failures`() {
        val notice = StoppedCaptionsNotice()
        val down = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        notice.update(down, appIsActive = false, isEnabled = true, now = 0.0)
        notice.update(null, appIsActive = false, isEnabled = true, now = 5.0)
        assertEquals(55.0, scheduledDelay(notice.update(down, appIsActive = false, isEnabled = true, now = 30.0)))
        notice.update(null, appIsActive = false, isEnabled = true, now = 35.0)
        val later = 35.0 + StoppedCaptionsNotice.SOUND_ALERTS_WINDOW_SECONDS + 1
        assertEquals(60.0, scheduledDelay(notice.update(down, appIsActive = false, isEnabled = true, now = later)))
    }

    @Test
    fun `time waited on a notice that could not be shown still counts toward the minute`() {
        val notice = StoppedCaptionsNotice()
        val down = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        notice.update(down, appIsActive = false, isEnabled = true, now = 0.0)
        notice.postFailed(now = 30.0)
        assertEquals(30.0, scheduledDelay(notice.update(down, appIsActive = false, isEnabled = true, now = 31.0)))
    }

    @Test
    fun `the other-app notice says another app is using the microphone and what to do`() {
        val body = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone).body
        assertTrue(body.contains("אפליקציה אחרת"))
        assertTrue(!body.contains("שיחת הטלפון"))
        Localization.withLanguage(UILanguage.English) {
            val content = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.OtherAppHasMicrophone)
            assertEquals("Captions paused", content.title)
            assertTrue(content.body.contains("Another app"))
            assertTrue(content.body.contains("sound alerts"))
            assertTrue(content.body.contains("Open Ozen"))
            assertTrue(!content.body.contains("After the call"))
        }
    }

    @Test
    fun `posted once while the phone is put away, never while the app is on screen or switched off`() {
        val notice = StoppedCaptionsNotice()
        assertEquals(
            StoppedCaptionsNotice.Update.Withdraw(StoppedCaptionsNotice.IDENTIFIER),
            notice.update(failed(glitch), appIsActive = true, isEnabled = true),
        )
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
        assertEquals(
            StoppedCaptionsNotice.Update.Withdraw(StoppedCaptionsNotice.IDENTIFIER),
            notice.update(null, appIsActive = false, isEnabled = true),
        )
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
    fun `a plain ws address out on the internet points to the wss one, not to a computer that may well be on`() {
        val plain = PipelineFailure(
            PipelineFailure.Kind.EngineUnavailable, "",
            EngineUnavailability(EngineUnavailability.Kind.HomeServerUnreachable, HomeServer.NEEDS_ENCRYPTION),
        )
        val body = StoppedCaptionsNotice.content(failed(plain)).body
        assertTrue(body.contains("wss://"), body)
        assertTrue(!body.contains("אין תשובה מהמחשב"), body)
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

        assertTrue(callEnded.contains("אחרי שיחת הטלפון"))
        assertEquals(speechPermission, permission)
        assertTrue(permission.contains("הרשאה"))
        assertTrue(storage.contains("מקום"))
        assertTrue(noMic.contains("מיקרופון"))
        // Running out of credit is not a key to check: whoever helps her
        // tops up the account instead.
        assertTrue(cloudKey != cloudCredit)
        assertTrue(cloudCredit.contains("הקרדיט") && !cloudCredit.contains("מפתח"))
        assertTrue(offline.contains("אינטרנט"))
        assertTrue(offline.contains("זיהוי הדיבור שבטלפון") && !offline.contains("Whisper"))
        // Opening the app doesn't wake the computer; captions come back by
        // themselves once it answers.
        assertTrue(computer.contains("אין תשובה מהמחשב"))
        val language = StoppedCaptionsNotice.content(failed(engineFailure(EngineUnavailability.Kind.CloudLanguageNotCovered))).body
        assertEquals("השירות הזה לא מתמלל את השפה הזו. פתחו את אוזן ובחרו שירות אחר.", language)
        assertEquals(10, setOf(callEnded, permission, storage, noMic, other, cloudKey, cloudCredit, offline, computer, language).size)
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

    @Test
    fun `a fresh instance withdraws a notice left by a killed process, once`() {
        val notice = StoppedCaptionsNotice()
        assertEquals(
            StoppedCaptionsNotice.Update.Withdraw("captions-stopped"),
            notice.update(null, appIsActive = false, isEnabled = true),
        )
        assertNull(notice.update(null, appIsActive = false, isEnabled = true))
    }

    @Test
    fun `a fresh instance with a cause posts and never sweeps on the post path`() {
        val notice = StoppedCaptionsNotice()
        assertIs<StoppedCaptionsNotice.Update.Post>(notice.update(failed(glitch), appIsActive = false, isEnabled = true))
        assertEquals(
            StoppedCaptionsNotice.Update.Withdraw(StoppedCaptionsNotice.IDENTIFIER),
            notice.update(null, appIsActive = false, isEnabled = true),
        )
        assertNull(notice.update(null, appIsActive = false, isEnabled = true))
    }

    @Test
    fun `a notice that could not be shown is tried again`() {
        val notice = StoppedCaptionsNotice()
        assertIs<StoppedCaptionsNotice.Update.Post>(notice.update(failed(glitch), appIsActive = false, isEnabled = true))
        notice.postFailed()
        assertIs<StoppedCaptionsNotice.Update.Post>(notice.update(failed(glitch), appIsActive = false, isEnabled = true))
        assertNull(notice.update(failed(glitch), appIsActive = false, isEnabled = true))
    }

    @Test
    fun `when the phone ended the app and it cannot listen again the notice says the phone refused and what to tap`() {
        val content = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.EndedByPhone)
        assertEquals(
            tr("הטלפון לא איפשר לאוזן להאזין. לחצו על התחלת כתוביות כדי לנסות שוב.", "The phone wouldn’t let Ozen listen. Tap Start captions to try again."),
            content.body,
        )
        assertEquals(StoppedCaptionsNotice.IDENTIFIER, content.identifier)
        assertTrue(content.isUrgent)
        val notice = StoppedCaptionsNotice()
        assertIs<StoppedCaptionsNotice.Update.Post>(
            notice.update(StoppedCaptionsNotice.Cause.EndedByPhone, appIsActive = false, isEnabled = true),
        )
    }

    @Test
    fun `sound alerts that can't work are a cause while captions run, are retried or wait, and not otherwise`() {
        val notWorking = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        assertEquals(notWorking, cause(PipelinePhase.Listening, soundAlertsNotWorking = true))
        assertEquals(notWorking, cause(PipelinePhase.Idle, soundsOnly = true, soundAlertsNotWorking = true))
        assertEquals(notWorking, cause(PipelinePhase.Failed(glitch), retryScheduled = true, soundAlertsNotWorking = true))
        assertEquals(notWorking, cause(PipelinePhase.Failed(engineFailure(EngineUnavailability.Kind.WaitingForWiFi)), soundAlertsNotWorking = true))
        assertEquals(failed(glitch), cause(PipelinePhase.Failed(glitch), soundAlertsNotWorking = true))
        assertNull(cause(PipelinePhase.Listening, interrupted = true, byCall = true, soundAlertsNotWorking = true))
        assertNull(cause(PipelinePhase.Listening))
    }

    @Test
    fun `the not-working notice goes out after a minute of it not working, once, and is cancelled when sound alerts work again or the app opens`() {
        val notice = StoppedCaptionsNotice()
        val notWorking = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        assertEquals(60.0, StoppedCaptionsNotice.SOUND_ALERTS_DELAY_SECONDS)
        notice.update(notWorking, appIsActive = true, isEnabled = true)
        assertNull(notice.update(notWorking, appIsActive = false, isEnabled = false))
        val scheduled = notice.update(notWorking, appIsActive = false, isEnabled = true)
        if (scheduled !is StoppedCaptionsNotice.Update.Schedule) fail("expected a notification to schedule")
        assertEquals(60.0, scheduled.afterSeconds)
        assertEquals(StoppedCaptionsNotice.IDENTIFIER, scheduled.content.identifier)
        assertTrue(scheduled.content.isUrgent)
        assertNull(notice.update(notWorking, appIsActive = false, isEnabled = true))
        assertEquals(
            StoppedCaptionsNotice.Update.Cancel(StoppedCaptionsNotice.IDENTIFIER),
            notice.update(null, appIsActive = false, isEnabled = true),
        )
        assertIs<StoppedCaptionsNotice.Update.Schedule>(
            notice.update(notWorking, appIsActive = false, isEnabled = true),
            "a new stop should be told again",
        )
        assertEquals(
            StoppedCaptionsNotice.Update.Cancel(StoppedCaptionsNotice.IDENTIFIER),
            notice.update(notWorking, appIsActive = true, isEnabled = true),
        )
    }

    @Test
    fun `the not-working notice says sounds may be missed and that Ozen keeps trying`() {
        val content = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking)
        assertEquals("ההתראות על צלילים לא פועלות", content.title)
        assertTrue(content.body.contains("ממשיכה לנסות"))
        Localization.withLanguage(UILanguage.English) {
            val english = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking)
            assertEquals("Sound alerts aren’t working", english.title)
            assertTrue(english.body.contains("keeps trying"))
            assertTrue(english.body.contains("doorbell"))
        }
    }

    @Test
    fun `a refused pairing has its own words that say to scan the QR code again, a changed certificate included`() {
        fun body(detail: String) = StoppedCaptionsNotice.content(
            failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", EngineUnavailability(EngineUnavailability.Kind.HomeServerRejected, detail))),
        ).body
        Localization.withLanguage(UILanguage.English) {
            assertEquals("The computer’s pairing changed. Open Ozen and scan its QR code again.", body(HomeServer.PAIRING_CHANGED))
            assertEquals("The computer didn’t accept the pairing code. Open Ozen and scan its QR code again.", body("unauthorized"))
        }
    }

    @Test
    fun `a phone with no saved pairing code says so, since no code was sent to be refused`() {
        val why = EngineUnavailability(EngineUnavailability.Kind.HomeServerRejected, HomeServer.NO_CODE)
        Localization.withLanguage(UILanguage.English) {
            assertEquals(
                "No pairing code for the home computer is saved on this phone. Open Ozen and scan its QR code.",
                StoppedCaptionsNotice.content(failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", why))).body,
            )
        }
    }

    @Test
    fun `a cloud that did not answer in time is not called a lost internet`() {
        fun body(why: EngineUnavailability) =
            StoppedCaptionsNotice.content(failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", why))).body
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Cloud transcription isn’t available right now. Open Ozen to continue.", body(CloudSpeechError.TimedOut.unavailability))
            assertTrue(body(CloudSpeechError.Offline.unavailability).startsWith("There’s no internet"))
        }
    }

    @Test
    fun `a phone with no saved address does not tell her to check that the computer is on`() {
        val why = EngineUnavailability(EngineUnavailability.Kind.HomeServerUnreachable, HomeServer.NO_ADDRESS)
        Localization.withLanguage(UILanguage.English) {
            assertEquals(
                "The address or code is missing. Open Ozen to continue.",
                StoppedCaptionsNotice.content(failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", why))).body,
            )
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

    @Test
    fun `a delivered not-working notice survives a recovery, but not sound alerts being turned off, paused or stopped`() {
        val kept = StoppedCaptionsNotice()
        kept.update(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking, appIsActive = false, isEnabled = true, now = 0.0)
        assertNull(kept.update(null, appIsActive = false, isEnabled = true, soundAlertsMeantToRun = true, now = 70.0))

        val off = StoppedCaptionsNotice()
        off.update(StoppedCaptionsNotice.Cause.SoundAlertsNotWorking, appIsActive = false, isEnabled = true, now = 0.0)
        assertEquals(
            StoppedCaptionsNotice.Update.Cancel(StoppedCaptionsNotice.IDENTIFIER),
            off.update(null, appIsActive = false, isEnabled = true, soundAlertsMeantToRun = false, now = 70.0),
        )
        assertNull(off.update(null, appIsActive = false, isEnabled = true, soundAlertsMeantToRun = false, now = 80.0))
    }
}
