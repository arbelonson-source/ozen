package com.arbelonson.ozen.core

/**
 * Tells her, once, that captions stopped while the phone was put away.
 *
 * With the phone in a pocket or on the table with the screen off, the
 * doorbell and name alerts are the whole point of leaving captions on.
 * When captions stop there and nothing will bring them back by itself,
 * those alerts stop too, and nothing on a dark screen says so. Two ways
 * that happens:
 *
 * - a failure automatic recovery won't retry, or has given up on;
 * - a phone call ends but the system never hands the microphone back, which
 *   it doesn't promise to do.
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
    }

    sealed class Update {
        data class Post(val content: AlertNotificationContent) : Update()

        data class Withdraw(val identifier: String) : Update()
    }

    /** A notice is sitting in the notification shade. */
    private var posted = false

    /**
     * She has been told about this stop: by the notice, or by opening the
     * app and seeing the status. Not told again until captions run.
     */
    private var told = false

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
        if (cause == null) {
            told = false
            return withdrawIfPosted()
        }
        if (appIsActive) {
            return withdrawIfPosted()
        }
        if (!isEnabled || told) return null
        posted = true
        told = true
        return Update.Post(content(cause))
    }

    private fun withdrawIfPosted(): Update? {
        if (!posted) return null
        posted = false
        return Update.Withdraw(IDENTIFIER)
    }

    companion object {
        const val IDENTIFIER = "captions-stopped"

        /**
         * Why captions are stopped for good right now, or null when they run,
         * are on their way back, or were paused on purpose.
         *
         * [callEndedDuringInterruption] is true once a call that took the
         * microphone is known to be over while the system still hasn't said the
         * interruption ended. Until then an interruption is someone on the
         * phone, which is no news to her.
         */
        fun cause(
            phase: PipelinePhase,
            retryScheduled: Boolean,
            systemInterrupted: Boolean,
            callEndedDuringInterruption: Boolean,
        ): Cause? {
            if (systemInterrupted) {
                // A failure during the call is retried once the call gives the
                // microphone back, so what matters is only whether it did.
                if (!callEndedDuringInterruption || !(phase.isListening || phase.failure != null)) return null
                return Cause.CallEnded
            }
            val failure = phase.failure
            if (failure == null || retryScheduled) return null
            // Starts by itself as soon as the phone is on Wi-Fi.
            if (failure.engineUnavailability?.kind == EngineUnavailability.Kind.WaitingForWiFi) return null
            return Cause.Failed(failure)
        }

        internal fun content(cause: Cause): AlertNotificationContent = AlertNotificationContent(
            identifier = IDENTIFIER,
            title = tr("הכתוביות נעצרו", "Captions stopped"),
            body = body(cause),
            threadIdentifier = "status",
            isUrgent = true,
        )

        private fun body(cause: Cause): String = when (cause) {
            Cause.CallEnded -> tr(
                "אחרי השיחה הכתוביות לא חזרו לבד. פתחו את אוזן כדי להמשיך.",
                "After the call, captions didn't come back on their own. Open Ozen to continue.",
            )
            is Cause.Failed -> {
                val failure = cause.failure
                val why = failure.engineUnavailability?.kind
                when {
                    failure.kind == PipelineFailure.Kind.MicrophonePermissionDenied ||
                        why == EngineUnavailability.Kind.PermissionDenied -> tr(
                        "לאוזן אין הרשאה להקשיב. פתחו את האפליקציה כדי לתקן.",
                        "Ozen doesn't have permission to listen. Open the app to fix it.",
                    )
                    why == EngineUnavailability.Kind.NotEnoughStorage -> tr(
                        "אין מספיק מקום בטלפון. פתחו את אוזן לפרטים.",
                        "There isn't enough space on the phone. Open Ozen for details.",
                    )
                    failure.kind == PipelineFailure.Kind.NoAudioInputs -> tr(
                        "לא נמצא מיקרופון. פתחו את אוזן כדי להמשיך.",
                        "No microphone was found. Open Ozen to continue.",
                    )
                    why == EngineUnavailability.Kind.CloudKeyNeeded -> tr(
                        "יש בעיה במפתח של התמלול בענן. פתחו את אוזן לפרטים.",
                        "There's a problem with the cloud transcription key. Open Ozen for details.",
                    )
                    why == EngineUnavailability.Kind.CloudOutOfCredit -> tr(
                        "נגמר התקציב לתמלול בענן. פתחו את אוזן לפרטים.",
                        "Cloud transcription’s budget ran out. Open Ozen for details.",
                    )
                    why == EngineUnavailability.Kind.NoInternet -> tr(
                        "אין אינטרנט, והתמלול בענן צריך אותו. פתחו את אוזן ונסו שוב, או בקשו ממי שהתקין את הטלפון לעבור לזיהוי הדיבור שבטלפון.",
                        "There's no internet, and cloud transcription needs it. Open Ozen and try again, or ask whoever set up the phone to switch to the phone's own speech recognition.",
                    )
                    why == EngineUnavailability.Kind.HomeServerUnreachable -> tr(
                        "אין תשובה מהמחשב. בדקו שהוא דלוק, ער (לא במצב שינה) ומחובר לאינטרנט.",
                        "No answer from the computer. Check that it’s on, awake (not asleep) and connected to the internet.",
                    )
                    else -> tr("פתחו את אוזן כדי להמשיך.", "Open Ozen to continue.")
                }
            }
        }
    }
}
