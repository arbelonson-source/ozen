package com.arbelonson.ozen.core

import java.text.BreakIterator
import java.util.Locale

/**
 * Decides when an alert should also become a phone notification.
 *
 * On screen, a doorbell or her name buzzes and shows a banner. With the
 * phone in a pocket or the screen locked, captions keep running but none
 * of that is seen. So while the app is not in front, alerts turn into
 * notifications. They stay quiet while the app is in front (the banner
 * already shows), and the same sound or word notifies at most once per
 * cooldown, because a name said five times in a minute is one thing to
 * look at.
 */
class BackgroundAlertPolicy(
    var isEnabled: Boolean = true,
    var cooldownSeconds: Double = 30.0,
    /**
     * While this window is on, only a critical sound still notifies: her
     * name, an ordinary sound, wait until it ends.
     */
    var quietHours: QuietHours = QuietHours(),
) {
    private val lastNotified = HashMap<String, Double>()

    fun notification(alert: SoundAlert, appIsActive: Boolean, now: Double, utcOffsetSeconds: Int = 0): AlertNotificationContent? {
        val critical = alert.event.importance == SoundEvent.Importance.Critical
        if (!critical && quietHours.isQuiet(now, utcOffsetSeconds)) return null
        // By the same key as SoundEventPolicy's own cooldown, not the
        // identifier or the name: two classifier labels for one sound (a
        // ringtone and a phone ringing) must share it, or the classifier
        // flipping labels notifies twice. The names only match in Hebrew.
        val key = "sound-${alert.event.cooldownKey}"
        if (!shouldNotify(key, appIsActive, now)) return null
        return AlertNotificationContent(
            identifier = key,
            title = alert.event.name,
            body = if (critical) {
                tr("שימו לב! נשמע עכשיו ליד הטלפון.", "Attention! Heard just now near the phone.")
            } else {
                tr("נשמע עכשיו ליד הטלפון.", "Heard just now near the phone.")
            },
            threadIdentifier = "sounds",
            isUrgent = critical,
        )
    }

    fun notification(hit: KeywordHit, lineText: String, appIsActive: Boolean, now: Double, utcOffsetSeconds: Int = 0): AlertNotificationContent? {
        if (quietHours.isQuiet(now, utcOffsetSeconds)) return null
        val key = "keyword-${hit.match.alertID.toString().uppercase()}"
        if (!shouldNotify(key, appIsActive, now)) return null
        return AlertNotificationContent(
            identifier = key,
            title = tr("נאמר: %1", "Said: %1", listOf(AlertSuggestions.shown(hit.match.phrase, Localization.language))),
            // On the lock screen too, a line opening with an English word
            // would otherwise read out of order, and a phone number said in
            // it would read from its last group ("4567 123 050").
            body = rightToLeft(CaptionLayout.isolatingNumbers(excerpt(lineText))),
            threadIdentifier = "keywords",
            isUrgent = false,
        )
    }

    private fun shouldNotify(key: String, appIsActive: Boolean, now: Double): Boolean {
        if (!isEnabled || appIsActive) return false
        // `now` is wall-clock time, which can go backward (an NTP sync, a
        // manual clock change). Without the `now >= last` guard, a
        // backward jump makes `now - last` deeply negative, always "under"
        // the cooldown, silently suppressing a genuinely new alert until
        // real time catches back up to where the clock used to read.
        val last = lastNotified[key]
        if (last != null && now >= last && now - last < cooldownSeconds) return false
        lastNotified[key] = now
        return true
    }

    companion object {
        /**
         * Whether to ask for notification permission now. Alerts with the
         * screen off are on by default, but the only ask was a skippable
         * onboarding button: skipped, the system never asked, and every
         * locked-phone alert was dropped while Settings showed nothing
         * wrong. [allowed] is null while nobody has answered; a "no" is
         * never asked again.
         */
        fun shouldAskPermission(alertsWhenScreenOff: Boolean, allowed: Boolean?): Boolean =
            alertsWhenScreenOff && allowed == null

        /**
         * What a sound alert looks like on the lock screen, for trying it
         * out from Settings: Focus modes, notification summaries and a muted
         * app can each keep the real ones away, and the time to find that
         * out is not when the doorbell rings.
         */
        val testNotification: AlertNotificationContent
            get() = AlertNotificationContent(
                identifier = "test-alert",
                title = tr("בדיקה: פעמון דלת", "Test: doorbell"),
                body = tr(
                    "כך תיראה התראה מאוזן כשהטלפון בכיס או נעול.",
                    "This is what a notification from Ozen looks like when the phone is in a pocket or locked.",
                ),
                threadIdentifier = "sounds",
                isUrgent = false,
            )

        private fun rightToLeft(text: String): String =
            if (CaptionLayout.opensLeftToRight(text)) CaptionLayout.RIGHT_TO_LEFT_MARK + text else text

        private fun isPlainSpace(character: Char): Boolean = character.isWhitespace() && character != '\n' && character != '\r'

        // Notification bodies get cut off by the system anyway; cut at a
        // word so the reader sees whole words and an ellipsis.
        internal fun excerpt(text: String, limit: Int = 120): String {
            val trimmed = text.trim { it.isWhitespace() }
            val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
            boundaries.setText(trimmed)
            var clippedEnd = boundaries.first()
            repeat(limit) {
                clippedEnd = boundaries.next()
                if (clippedEnd == BreakIterator.DONE) return trimmed
            }
            if (boundaries.next() == BreakIterator.DONE) return trimmed
            val clipped = trimmed.substring(0, clippedEnd)
            val space = clipped.lastIndexOf(' ')
            val atWord = if (space >= 0) clipped.substring(0, space) else clipped
            return atWord.trim { isPlainSpace(it) } + "…"
        }
    }
}
