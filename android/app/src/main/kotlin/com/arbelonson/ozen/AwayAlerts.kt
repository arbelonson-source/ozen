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
import android.provider.Settings
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

    fun takePostedIdentifiers(): List<String> = policy.takePostedIdentifiers()

    companion object {
        /** The notice with this identifier is among the notification tags still in the shade. */
        /**
         * The time a notice carries in its header: a revision of one still
         * showing keeps the time it first had, since the notice only has its
         * words brought up to date; anything else is stamped now.
         */
        fun timeToShow(active: List<Pair<String?, Long>>, identifier: String, now: Long, revising: Boolean): Long =
            if (revising) active.firstOrNull { it.first == identifier }?.second ?: now else now

        fun isShowing(activeTags: List<String?>, identifier: String): Boolean = identifier in activeTags

        fun vibrationFor(content: AlertNotificationContent): AlertVibration =
            if (content.isUrgent && content.threadIdentifier == AlertNotifier.SOUNDS_THREAD) AlertVibration.pattern(SoundEvent.Importance.Critical) else AlertVibration.keyword
    }
}

class AlertNotifier(private val context: Context) {
    fun post(content: AlertNotificationContent): Boolean = deliver(content).posted

    /**
     * Changes the words of a notice that is still showing, with no sound, buzz
     * or heads-up. One she swiped away is not brought back; that counts as done.
     */
    fun revise(content: AlertNotificationContent): Boolean {
        val showing = context.getSystemService(NotificationManager::class.java).activeNotifications
        if (!AwayAlerts.isShowing(showing.map { it.tag }, content.identifier)) return true
        return deliver(content, quiet = true, showing = showing.map { it.tag to it.notification.`when` }).posted
    }

    /**
     * Changes the words of a notice that is still showing and alerts again,
     * keeping the time it first had. One she swiped away is not brought back.
     */
    fun realert(content: AlertNotificationContent): Delivery {
        val showing = context.getSystemService(NotificationManager::class.java).activeNotifications
        if (!AwayAlerts.isShowing(showing.map { it.tag }, content.identifier)) return Delivery(true)
        return deliver(content, showing = showing.map { it.tag to it.notification.`when` }, keepTime = true)
    }

    class Delivery(val posted: Boolean, val buzz: AlertVibration? = null)

    fun deliver(content: AlertNotificationContent, quiet: Boolean = false, showing: List<Pair<String?, Long>> = emptyList(), keepTime: Boolean = quiet): Delivery {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarm = content.isUrgent && content.threadIdentifier == SOUNDS_THREAD
        val vibration = AwayAlerts.vibrationFor(content)
        val shown = if (alarm) alarmsPostable(context) else allowed(context)
        if (!shown) return Delivery(false)
        val channel = if (alarm) {
            alarmChannel()
        } else {
            alertsChannel(vibration)
        }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // A sound's name or "captions stopped" holds nothing private, and a
        // locked phone that hides contents left a smoke alarm looking like a
        // doorbell. A word alert's line can be private: on such a phone it
        // shows only "Said: <word>".
        val words = content.threadIdentifier == KEYWORDS_THREAD
        // The time it was heard, in the header: Android leaves it out unless
        // asked, so a doorbell found on the lock screen an hour later still
        // read "Heard just now", and an old alarm looked like a new one.
        val heard = AwayAlerts.timeToShow(showing, content.identifier, System.currentTimeMillis(), revising = keepTime)
        val notification = Notification.Builder(context, channel.id)
            .setWhen(heard)
            .setShowWhen(true)
            .apply { if (alarm) setCategory(Notification.CATEGORY_ALARM) }
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(Notification.BigTextStyle().bigText(content.body))
            .setGroup(content.threadIdentifier)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setVisibility(if (words) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .apply {
                if (words) {
                    setPublicVersion(
                        Notification.Builder(context, channel.id)
                            .setWhen(heard)
                            .setShowWhen(true)
                            .setSmallIcon(R.drawable.ic_listening)
                            .setContentTitle(content.title)
                            .build(),
                    )
                }
            }
            .build()
        manager.notify(content.identifier, 0, notification)
        if (quiet) return Delivery(true)
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

    fun withdrawAwayAlerts() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { BackgroundAlertPolicy.isAwayAlertTag(it.tag) }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /**
     * Posts the test alert after the 10 seconds she has to lock the phone.
     * That wait counts only while the processor is awake, and a locked phone
     * on battery soon sleeps, so the test came late or not at all and she
     * could decide alerts don't work. A short wake lock keeps it on time.
     */
    fun postTest(content: AlertNotificationContent = BackgroundAlertPolicy.testNotification) {
        val lock = testLock(context)
        lock.acquire(TEST_DELAY_MILLIS + TEST_LOCK_SPARE_MILLIS)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                post(content)
            } finally {
                lock.release()
            }
        }, TEST_DELAY_MILLIS)
    }

    companion object {
        const val CHANNEL_ID = "alerts"
        const val ALARM_CHANNEL_ID = "alarms"
        const val SOUNDS_THREAD = "sounds"
        const val KEYWORDS_THREAD = "keywords"
        const val TEST_DELAY_MILLIS = 10_000L
        const val TEST_LOCK_SPARE_MILLIS = 5_000L

        var testLock: (Context) -> TimedLock = { partialWakeLock(it, "ozen:test-alert") }

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

        fun alertsChannel(vibration: AlertVibration = AlertVibration.pattern(SoundEvent.Importance.Medium)): NotificationChannel =
            NotificationChannel(CHANNEL_ID, tr("התראות", "Alerts"), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = vibration.waveform().first
            }

        fun alarmChannel(): NotificationChannel =
            NotificationChannel(ALARM_CHANNEL_ID, tr("חירום", "Emergency"), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = AlertVibration.pattern(SoundEvent.Importance.Critical).waveform().first
            }

        /**
         * The phone's own page for the Emergency channel, where it can be
         * let through Do Not Disturb (an app can't do that for itself). The
         * channel is made first, so the page is there before any alarm.
         */
        fun alarmSettings(context: Context): Intent {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(alarmChannel())
            return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, ALARM_CHANNEL_ID)
        }

        var alarmsOverrideDoNotDisturb: (Context) -> Boolean = { context ->
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(ALARM_CHANNEL_ID)
            alarmsGetThrough(manager.areNotificationsEnabled(), channel?.importance, channel?.canBypassDnd() == true, channel?.sound != null, channel?.shouldVibrate() != false)
        }

        /**
         * The phone keeps a channel's Do Not Disturb setting after the channel
         * itself is turned off, and a silenced channel makes no sound or buzz
         * either: urgent sounds come through only while the channel is loud.
         */
        fun alarmsGetThrough(notificationsOn: Boolean, importance: Int?, bypassesDnd: Boolean, hasSound: Boolean = true, vibrates: Boolean = true): Boolean =
            notificationsOn && importance != null && EmergencyChannelNotice.loud(importance, hasSound, vibrates) && bypassesDnd

        var openAlarmSettings: (Context) -> Unit = { context -> context.startActivity(alarmSettings(context)) }

        /**
         * Whether the phone sounds Ozen's Emergency alerts: shown, and not
         * set to silent. A channel not made yet counts as on:
         * [alarmSettings] makes it on.
         */
        fun alarmsOn(notificationsOn: Boolean, importance: Int?, hasSound: Boolean = true, vibrates: Boolean = true): Boolean =
            notificationsOn && EmergencyChannelNotice.loud(importance, hasSound, vibrates)

        var alarmsAllowed: (Context) -> Boolean = { context ->
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(ALARM_CHANNEL_ID)
            alarmsOn(manager.areNotificationsEnabled(), channel?.importance, channel?.sound != null, channel?.shouldVibrate() != false)
        }

        /** A silent alarm is still posted, so it can be seen; only a blocked channel drops it. */
        var alarmsPostable: (Context) -> Boolean = { context ->
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.areNotificationsEnabled() && manager.getNotificationChannel(ALARM_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        var emergencyWarning: (Context, Boolean, Boolean) -> EmergencyChannelNotice.Warning = { context, soundAlertsOn, awayBannerShowing ->
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(ALARM_CHANNEL_ID)
            EmergencyChannelNotice.warning(soundAlertsOn, awayBannerShowing, manager.areNotificationsEnabled(), channel?.importance, channel?.sound != null, channel?.shouldVibrate() != false)
        }

        var allowed: (Context) -> Boolean = { context ->
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
