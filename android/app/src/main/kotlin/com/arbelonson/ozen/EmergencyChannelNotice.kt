package com.arbelonson.ozen

import android.app.NotificationManager
import com.arbelonson.ozen.core.tr

object EmergencyChannelNotice {
    enum class Warning { None, Off, Silent }

    /** Quiet by importance, or with both sound and buzz off, which a sleeping person cannot notice. */
    fun loud(importance: Int?, hasSound: Boolean = true, vibrates: Boolean = true): Boolean =
        (hasSound || vibrates) &&
            (importance == null || importance == NotificationManager.IMPORTANCE_UNSPECIFIED || importance >= NotificationManager.IMPORTANCE_DEFAULT)

    /**
     * What the caption screen says about the Emergency channel. Nothing
     * when sound alerts are off. When all of Ozen's notifications are off and
     * the notifications-off banner is already up, that banner says it.
     */
    fun warning(soundAlertsOn: Boolean, awayBannerShowing: Boolean, notificationsOn: Boolean, importance: Int?, hasSound: Boolean = true, vibrates: Boolean = true): Warning = when {
        !soundAlertsOn -> Warning.None
        !notificationsOn -> if (awayBannerShowing) Warning.None else Warning.Off
        importance == NotificationManager.IMPORTANCE_NONE -> Warning.Off
        !loud(importance, hasSound, vibrates) -> Warning.Silent
        else -> Warning.None
    }

    fun title(): String = tr("התראות חירום מושתקות", "Emergency alerts are muted")

    fun detail(warning: Warning): String = when (warning) {
        Warning.Silent -> tr(
            "התראות חירום במצב שקט בהגדרות הטלפון, ולכן התראה על אזעקת עשן לא תצלצל ולא תרטוט. הקישו כדי לתקן.",
            "Emergency alerts are set to silent in the phone’s settings, so a smoke alarm won’t sound or buzz. Tap to fix.",
        )
        else -> tr(
            "התראות חירום כבויות בהגדרות הטלפון, ולכן התראה על אזעקת עשן לא תגיע אליכם. הקישו כדי להפעיל אותן.",
            "Emergency alerts are turned off in the phone’s settings, so a smoke alarm won’t reach you. Tap to turn them on.",
        )
    }
}
