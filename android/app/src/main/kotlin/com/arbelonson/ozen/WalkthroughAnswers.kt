package com.arbelonson.ozen

import com.arbelonson.ozen.core.MicrophoneAsk
import com.arbelonson.ozen.core.tr

internal object WalkthroughAnswers {
    fun microphoneApproved() = tr("המיקרופון מאושר", "Microphone approved")

    fun whyNotifications() = tr("ועוד דבר אחד: כשהטלפון בכיס או נעול, אוזן יכולה להודיע לכם על צלצול בדלת, על אזעקה או על קריאה בשמכם.", "One more thing: when the phone is in a pocket or locked, Ozen can notify you about a doorbell, an alarm, or your name.")

    fun microphoneBlocked() = tr("המיקרופון חסום", "Microphone blocked")

    fun blockedDetail() = tr("בלי מיקרופון אין כתוביות. אפשר לאשר גישה למיקרופון בהגדרות הטלפון.", "Without a microphone there are no captions. You can allow microphone access in the phone’s Settings.")

    fun noMicrophone() = tr("בלי מיקרופון אין כתוביות.", "Without a microphone there are no captions.")

    fun notificationsApproved() = tr("ההתראות מאושרות", "Notifications approved")

    fun noNotifications() = tr("ההתראות לא אושרו. אפשר לשנות את זה בהגדרות.", "No notifications. This can be changed in Settings.")

    fun said(before: Pair<MicrophoneAsk?, Boolean?>, now: Pair<MicrophoneAsk?, Boolean?>): List<String> {
        val (microphone, notifications) = now
        val microphoneSaid = microphone?.takeIf { it != before.first }?.let {
            when (it) {
                MicrophoneAsk.Granted -> microphoneApproved() + ". " + whyNotifications()
                MicrophoneAsk.OpenSettings -> microphoneBlocked() + ". " + blockedDetail()
                MicrophoneAsk.AskAgain -> noMicrophone()
            }
        }
        val notificationsSaid = notifications?.takeIf { microphone == MicrophoneAsk.Granted && it != before.second }?.let {
            if (it) notificationsApproved() else noNotifications()
        }
        return listOfNotNull(microphoneSaid, notificationsSaid)
    }
}
