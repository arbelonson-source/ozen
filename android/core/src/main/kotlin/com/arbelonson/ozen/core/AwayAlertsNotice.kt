package com.arbelonson.ozen.core

/**
 * With the phone's notifications for Ozen off, every alert meant for when
 * Ozen isn't on the screen (a doorbell, an alarm, a word, captions that
 * stopped) goes nowhere, so the caption screen has to say so.
 */
object AwayAlertsNotice {
    fun blocked(awayAlertsOn: Boolean, notificationsAllowed: Boolean): Boolean = awayAlertsOn && !notificationsAllowed

    fun title(): String = tr("ההתראות כבויות", "Notifications are off")

    fun detail(): String = tr(
        "התראות לא יגיעו אליכם כשאוזן לא על המסך. הקישו כדי לפתוח את הגדרות הטלפון.",
        "Alerts won’t reach you when Ozen isn’t on the screen. Tap to open the phone’s settings.",
    )
}
