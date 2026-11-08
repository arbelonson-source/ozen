package com.arbelonson.ozen

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.LocaleList
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.tr

object StartCaptionsRequest {
    const val ACTION = "com.arbelonson.ozen.action.START_CAPTIONS"
    const val SHORTCUT_ID = "start-captions"

    fun launchIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun launchPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, launchIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun applyAppLanguage(settings: SettingsHolder) {
        val phoneLanguages = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }
        Localization.language = settings.current.value.appLanguage.resolved(phoneLanguages)
    }

    fun publishShortcut(context: Context) {
        val label = tr("התחלת כתוביות", "Start captions")
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).setAction(ACTION))
            .build()
        ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut))
    }

    fun shouldStart(
        action: String?,
        newIntent: Boolean,
        recreated: Boolean,
        fromHistory: Boolean,
        walkthroughShowing: Boolean,
        crashGuardAllows: Boolean,
        firstAppearancePending: Boolean,
    ): Boolean {
        if (action != ACTION) return false
        if (walkthroughShowing) return false
        if (!crashGuardAllows) return false
        if (newIntent) return true
        if (recreated || fromHistory) return false
        return !firstAppearancePending
    }
}
