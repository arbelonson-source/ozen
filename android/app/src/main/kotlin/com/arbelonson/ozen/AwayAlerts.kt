package com.arbelonson.ozen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.arbelonson.ozen.core.AlertNotificationContent
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.BackgroundAlertPolicy
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.SoundEvent
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

    companion object {
        fun vibrationFor(content: AlertNotificationContent): AlertVibration =
            if (content.isUrgent && content.threadIdentifier == AlertNotifier.SOUNDS_THREAD) AlertVibration.pattern(SoundEvent.Importance.Critical) else AlertVibration.keyword
    }
}

class AlertNotifier(private val context: Context) {
    fun post(content: AlertNotificationContent): Boolean = deliver(content).posted

    class Delivery(val posted: Boolean, val buzz: AlertVibration? = null)

    fun deliver(content: AlertNotificationContent): Delivery {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarm = content.isUrgent && content.threadIdentifier == SOUNDS_THREAD
        val vibration = AwayAlerts.vibrationFor(content)
        val shown = if (alarm) manager.areNotificationsEnabled() && manager.getNotificationChannel(ALARM_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE else allowed(context)
        if (!shown) return Delivery(false)
        val channel = if (alarm) {
            NotificationChannel(ALARM_CHANNEL_ID, tr("חירום", "Emergency"), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = vibration.waveform().first
            }
        } else {
            NotificationChannel(CHANNEL_ID, tr("התראות", "Alerts"), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = vibration.waveform().first
            }
        }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, channel.id)
            .apply { if (alarm) setCategory(Notification.CATEGORY_ALARM) }
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(Notification.BigTextStyle().bigText(content.body))
            .setGroup(content.threadIdentifier)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(content.identifier, 0, notification)
        val playing = manager.getNotificationChannel(channel.id) ?: channel
        val silenced = buzzMuted(
            playing.canBypassDnd(),
            manager.currentInterruptionFilter,
            context.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_SILENT,
        )
        return Delivery(true, channelBuzz(playing.shouldVibrate() && !silenced, playing.vibrationPattern, vibration))
    }

    fun withdraw(identifier: String) {
        context.getSystemService(NotificationManager::class.java).cancel(identifier, 0)
    }

    fun postTest() {
        Handler(Looper.getMainLooper()).postDelayed({ post(BackgroundAlertPolicy.testNotification) }, TEST_DELAY_MILLIS)
    }

    companion object {
        const val CHANNEL_ID = "alerts"
        const val ALARM_CHANNEL_ID = "alarms"
        const val SOUNDS_THREAD = "sounds"
        const val TEST_DELAY_MILLIS = 10_000L

        fun buzzMuted(canBypassDnd: Boolean, interruptionFilter: Int, ringerSilent: Boolean): Boolean {
            if (canBypassDnd) return false
            val quiet = interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            return quiet || ringerSilent
        }

        fun channelBuzz(shouldVibrate: Boolean, pattern: LongArray?, requested: AlertVibration): AlertVibration? {
            if (!shouldVibrate) return null
            if (pattern == null || pattern.size < 2) return requested
            val pulses = mutableListOf<AlertVibration.Pulse>()
            var at = 0.0
            for (i in pattern.indices step 2) {
                val start = at + pattern[i] / 1_000.0
                val length = (pattern.getOrNull(i + 1) ?: 0L) / 1_000.0
                if (length > 0) pulses += AlertVibration.Pulse(start, length, 1f, 1f)
                at = start + length
            }
            return AlertVibration(pulses)
        }

        var allowed: (Context) -> Boolean = { context ->
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
