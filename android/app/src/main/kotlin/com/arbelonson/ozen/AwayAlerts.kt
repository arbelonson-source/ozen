package com.arbelonson.ozen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.arbelonson.ozen.core.AlertNotificationContent
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.BackgroundAlertPolicy
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.tr

class AwayAlerts(private val policy: BackgroundAlertPolicy = BackgroundAlertPolicy()) {
    fun forWords(
        hits: List<KeywordHit>,
        line: String,
        appIsActive: Boolean,
        settings: AppSettings,
        now: Double,
        utcOffsetSeconds: Int,
    ): List<AlertNotificationContent> {
        policy.isEnabled = settings.notifyWhenInBackground
        policy.quietHours = settings.quietHours
        return hits.mapNotNull { policy.notification(it, line, appIsActive, now, utcOffsetSeconds) }
    }

    fun forSound(alert: SoundAlert, appIsActive: Boolean, settings: AppSettings, now: Double, utcOffsetSeconds: Int): AlertNotificationContent? {
        policy.isEnabled = settings.notifyWhenInBackground
        policy.quietHours = settings.quietHours
        return policy.notification(alert, appIsActive, now, utcOffsetSeconds)
    }
}

class AlertNotifier(private val context: Context) {
    fun post(content: AlertNotificationContent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("התראות", "Alerts"), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = AlertVibration.keyword.waveform().first
            },
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(Notification.BigTextStyle().bigText(content.body))
            .setGroup(content.threadIdentifier)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(content.identifier, 0, notification)
    }

    fun postTest() {
        Handler(Looper.getMainLooper()).postDelayed({ post(BackgroundAlertPolicy.testNotification) }, TEST_DELAY_MILLIS)
    }

    companion object {
        const val CHANNEL_ID = "alerts"
        const val TEST_DELAY_MILLIS = 10_000L

        var allowed: (Context) -> Boolean = { context ->
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
