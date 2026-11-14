package com.arbelonson.ozen.core

/**
 * Tells her, once, that captions stopped while the phone was put away.
 *
 * With the phone in a pocket or on the table with the screen off, the
 * doorbell and name alerts are the whole point of leaving captions on.
 * When captions stop there and nothing will bring them back by itself,
 * those alerts stop too, and nothing on a dark screen says so. Four ways
 * that happens:
 *
 * - a failure automatic recovery won't retry, or has given up on;
 * - a phone call ends but the system never hands the microphone back, which
 *   it doesn't promise to do;
 * - the phone ended the app and it came back without being able to listen;
 * - something that is not a call (an alarm app, another app recording) takes
 *   the microphone and, for a minute, doesn't give it back. The app does not
 *   take the microphone back from it, since that may be audio she chose; she
 *   is only told.
 *
 * The notice is withdrawn from the notification shade once captions run
 * again, or are paused or stopped on purpose, so an old "stopped" never
 * sits under a working app.
 */
class StoppedCaptionsNotice {
    sealed class Cause {
        /** Failed, and nothing is going to retry. */
        data class Failed(val failure: PipelineFailure) : Cause()

        /** The call is over, but the microphone didn't come back. */
        data object CallEnded : Cause()

        /** The phone ended the app and could not bring captions back by itself. */
        data object EndedByPhone : Cause()

        /** Sounds alone were listened for, and the microphone stopped and did not come back. */
        data object SoundAlertsStopped : Cause()

        /** Something other than a call has the microphone. */
        data object OtherAppHasMicrophone : Cause()

        /** Sound alerts are on, but the classifier keeps failing. */
        data object SoundAlertsNotWorking : Cause()
    }

    sealed class Update {
        data class Post(val content: AlertNotificationContent) : Update()

        data class Withdraw(val identifier: String) : Update()

        /** Deliver [content] after [afterSeconds] unless cancelled first. */
        data class Schedule(val content: AlertNotificationContent, val afterSeconds: Double) : Update()

        /** Drop the scheduled notice, and the delivered one if it went out. */
        data class Cancel(val identifier: String) : Update()
    }

    /** A notice is sitting in the notification shade. */
    private var posted = false

    /** What is sitting there was scheduled for later, not delivered now. */
    private var postedAsSchedule = false

    /**
     * She has been told about this stop: by the notice, or by opening the
     * app and seeing the status. Not told again until captions run.
     */
    private var told = false

    /** A fresh instance may follow a process Android killed after it posted. */
    private var firstUpdate = true

    /**
     * What to do with the phone's notifications given the current [cause].
     * Nothing is posted while the app is on screen (the status already
     * says it), or when she turned notifications from the app off.
     *
     * Opening the app while captions are still stopped takes the notice
     * away: the status says the same thing, and a notice left behind sat
     * in the notification shade for as long as the problem lasted. Putting
     * the phone away again doesn't post it a second time.
     */
    fun update(cause: Cause?, appIsActive: Boolean, isEnabled: Boolean): Update? {
        val first = firstUpdate
        firstUpdate = false
        if (cause == null) {
            told = false
            return withdrawIfPosted(sweep = first)
        }
        if (appIsActive) {
            return withdrawIfPosted(sweep = first)
        }
        if (!isEnabled || told) return null
        posted = true
        told = true
        if (cause == Cause.OtherAppHasMicrophone || cause == Cause.SoundAlertsNotWorking) {
            postedAsSchedule = true
            val delay = if (cause == Cause.SoundAlertsNotWorking) SOUND_ALERTS_DELAY_SECONDS else OTHER_APP_DELAY_SECONDS
            return Update.Schedule(content(cause), delay)
        }
        postedAsSchedule = false
        return Update.Post(content(cause))
    }

    /** The caller could not show the notice [update] returned, so it is tried again. */
    fun postFailed() {
        posted = false
        told = false
    }

    private fun withdrawIfPosted(sweep: Boolean): Update? {
        if (!posted && !sweep) return null
        val cancelling = posted && postedAsSchedule && !sweep
        posted = false
        return if (cancelling) Update.Cancel(IDENTIFIER) else Update.Withdraw(IDENTIFIER)
    }

    companion object {
        const val IDENTIFIER = "captions-stopped"

        /** How long another app has to keep the microphone before she is told. */
        const val OTHER_APP_DELAY_SECONDS = 60.0

        /** How long sound alerts have to keep failing before she is told while the app is away. */
        const val SOUND_ALERTS_DELAY_SECONDS = 60.0

        /**
         * Why captions are stopped for good right now, or null when they run,
         * are on their way back, or were paused on purpose.
         *
         * [callEndedDuringInterruption] is true once a call that took the
         * microphone is known to be over while the system still hasn't said the
         * interruption ended. Until then an interruption is someone on the
         * phone, which is no news to her. [interruptedByCall] is true when a
         * call is, or was during this interruption, going on; an interruption
         * without one is something else using the microphone. [soundsOnlyChosen]
         * is true while only sound alerts are listened for, which leaves the
         * phase idle or paused although something is running.
         */
        fun cause(
            phase: PipelinePhase,
            retryScheduled: Boolean,
            systemInterrupted: Boolean,
            callEndedDuringInterruption: Boolean,
            soundsOnlyStopped: Boolean = false,
            interruptedByCall: Boolean = false,
            soundsOnlyChosen: Boolean = false,
            soundAlertsNotWorking: Boolean = false,
        ): Cause? {
            val stop = stopCause(phase, retryScheduled, systemInterrupted, callEndedDuringInterruption, soundsOnlyStopped, interruptedByCall, soundsOnlyChosen)
            if (stop == null && soundAlertsNotWorking && !systemInterrupted) return Cause.SoundAlertsNotWorking
            return stop
        }

        private fun stopCause(
            phase: PipelinePhase,
            retryScheduled: Boolean,
            systemInterrupted: Boolean,
            callEndedDuringInterruption: Boolean,
            soundsOnlyStopped: Boolean,
            interruptedByCall: Boolean,
            soundsOnlyChosen: Boolean,
        ): Cause? {
            if (systemInterrupted) {
                // A failure during the call is retried once the call gives the
                // microphone back, so what matters is only whether it did.
                if (!(phase.isListening || phase.failure != null || soundsOnlyChosen)) return null
                if (callEndedDuringInterruption) return Cause.CallEnded
                return if (interruptedByCall) null else Cause.OtherAppHasMicrophone
            }
            if (soundsOnlyStopped) return Cause.SoundAlertsStopped
            val failure = phase.failure
            if (failure == null || retryScheduled) return null
            // Starts by itself as soon as the phone is on Wi-Fi.
            if (failure.engineUnavailability?.kind == EngineUnavailability.Kind.WaitingForWiFi) return null
            return Cause.Failed(failure)
        }

        internal fun content(cause: Cause): AlertNotificationContent = AlertNotificationContent(
            identifier = IDENTIFIER,
            title = when (cause) {
                Cause.SoundAlertsStopped -> tr("ההתראות על צלילים נעצרו", "Sound alerts stopped")
                Cause.SoundAlertsNotWorking -> soundAlertsNotWorkingTitle()
                Cause.OtherAppHasMicrophone -> tr("הכתוביות מושהות", "Captions paused")
                else -> tr("הכתוביות נעצרו", "Captions stopped")
            },
            body = body(cause),
            threadIdentifier = "status",
            isUrgent = true,
        )

        fun soundAlertsNotWorkingTitle(): String = tr("ההתראות על צלילים לא פועלות", "Sound alerts aren’t working")

        fun soundAlertsNotWorkingDetail(): String = tr(
            "הזיהוי של צלילים לא עובד כרגע, ולכן ייתכן שלא תקבלו התראה על צלצול בדלת, אזעקה או בכי של תינוק. אוזן ממשיכה לנסות.",
            "Ozen can’t recognize sounds right now, so you may miss a doorbell, an alarm or a baby crying. It keeps trying.",
        )

        private fun body(cause: Cause): String = when (cause) {
            Cause.SoundAlertsNotWorking -> soundAlertsNotWorkingDetail()
            Cause.OtherAppHasMicrophone -> tr(
                "אפליקציה אחרת משתמשת במיקרופון, ולכן הכתוביות וההתראות על צלילים מושהות. פתחו את אוזן כדי להחזיר אותן.",
                "Another app is using the microphone, so captions and sound alerts are paused. Open Ozen to bring them back.",
            )
            Cause.EndedByPhone -> tr("פתחו את אוזן כדי להמשיך.", "Open Ozen to continue.")
            Cause.SoundAlertsStopped -> tr(
                "המיקרופון הפסיק לפעול ולא חזר. פתחו את אוזן כדי להפעיל שוב את ההתראות על צלילים.",
                "The microphone stopped and didn’t come back. Open Ozen to turn sound alerts on again.",
            )
            Cause.CallEnded -> tr(
                "אחרי שיחת הטלפון הכתוביות לא חזרו לבד. פתחו את אוזן כדי להמשיך.",
                "After the call, captions didn’t come back on their own. Open Ozen to continue.",
            )
            is Cause.Failed -> {
                val failure = cause.failure
                val why = failure.engineUnavailability?.kind
                when {
                    failure.kind == PipelineFailure.Kind.MicrophonePermissionDenied ||
                        why == EngineUnavailability.Kind.PermissionDenied -> tr(
                        "לאוזן אין הרשאה להשתמש במיקרופון. פתחו את האפליקציה כדי לתקן את זה.",
                        "Ozen doesn’t have permission to listen. Open the app to fix it.",
                    )
                    why == EngineUnavailability.Kind.NotEnoughStorage -> tr(
                        "אין מספיק מקום בטלפון. פתחו את אוזן כדי לראות פרטים.",
                        "There isn’t enough space on the phone. Open Ozen for details.",
                    )
                    failure.kind == PipelineFailure.Kind.NoAudioInputs -> tr(
                        "לא נמצא מיקרופון. פתחו את אוזן כדי להמשיך.",
                        "No microphone was found. Open Ozen to continue.",
                    )
                    why == EngineUnavailability.Kind.CloudKeyNeeded -> tr(
                        "יש בעיה במפתח של התמלול בענן. פתחו את אוזן כדי לראות פרטים.",
                        "There’s a problem with the cloud transcription key. Open Ozen for details.",
                    )
                    why == EngineUnavailability.Kind.CloudLanguageNotCovered -> tr(
                        "השירות הזה לא מתמלל את השפה הזו. פתחו את אוזן ובחרו שירות אחר.",
                        "This service can’t caption this language. Open Ozen and choose another service.",
                    )
                    why == EngineUnavailability.Kind.CloudOutOfCredit -> tr(
                        "נגמר הקרדיט לתמלול בענן. פתחו את אוזן כדי לראות פרטים.",
                        "Cloud transcription’s budget ran out. Open Ozen for details.",
                    )
                    why == EngineUnavailability.Kind.NoInternet -> tr(
                        "אין חיבור לאינטרנט, והתמלול בענן לא יכול לעבוד בלעדיו. פתחו את אוזן ונסו שוב, או בקשו ממי שהגדיר את הטלפון לעבור לזיהוי הדיבור שבטלפון.",
                        "There’s no internet, and cloud transcription needs it. Open Ozen and try again, or ask whoever set up the phone to switch to the phone’s own speech recognition.",
                    )
                    HomeServer.refusedPlainAddress(failure.engineUnavailability) -> tr(
                        "מחוץ לרשת הביתית צריך להשתמש בכתובת שמתחילה ב־wss://",
                        "Outside the home network, use the address that starts with wss://",
                    )
                    why == EngineUnavailability.Kind.HomeServerUnreachable -> tr(
                        "אין תשובה מהמחשב. בדקו שהוא דלוק, לא במצב שינה ומחובר לאינטרנט.",
                        "No answer from the computer. Check that it’s on, awake (not asleep) and connected to the internet.",
                    )
                    else -> tr("פתחו את אוזן כדי להמשיך.", "Open Ozen to continue.")
                }
            }
        }
    }
}
