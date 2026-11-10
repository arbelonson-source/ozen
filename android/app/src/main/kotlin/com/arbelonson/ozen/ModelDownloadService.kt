package com.arbelonson.ozen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app going while a speech model chosen in Settings downloads. Out
 * of sight, Android freezes the app and cuts its network, so a model of a few
 * hundred megabytes stalled as soon as the person left Settings. A notification
 * is what asks Android not to; it goes when the download is over or given
 * up on, and stays while it waits for its next try, for Wi-Fi, or for a
 * connection.
 */
class ModelDownloadService : Service() {
    private val models get() = (application as OzenApplication).captions.phoneModels
    private val scope = MainScope()
    private var watching: Job? = null
    private var renewing: Job? = null
    private var wake: DownloadWakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        if (wake == null) wake = DownloadWakeLock.of(this)
        val shown = notification(this, models.switching.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, shown)
        }
        if (watching == null) watching = scope.launch { watch() }
        // The state moves with each whole percent; on a link slow enough to
        // stay on one for longer than the lock's time limit, this renews it.
        if (renewing == null) renewing = scope.launch { wake?.keepUp { models.switching.value } }
        return START_NOT_STICKY
    }

    /**
     * The notification follows the download at most once a second: the
     * collector waits after each post and then takes the latest state, so no
     * chunk off the network redraws it and the last change is never dropped.
     * A moment with nothing to show is waited out first, because a try that
     * starts again passes through one.
     */
    private suspend fun watch() {
        var posted: PhoneModelSwitch? = null
        models.switching.collect { state ->
            wake?.follow(state)
            wakeLockHeld = wake?.held == true
            if (state.keepsServiceUp) {
                if (state != posted) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this, state))
                    posted = state
                }
                delay(UPDATE_MILLISECONDS)
            } else {
                delay(QUIET_MILLISECONDS)
                if (!models.switching.value.keepsServiceUp) finish()
            }
        }
    }

    /** Android 15 caps how long a data sync service may run in a day; the download goes on as it would without one. */
    override fun onTimeout(startId: Int, fgsType: Int) = finish()

    override fun onTaskRemoved(rootIntent: Intent?) = finish()

    override fun onDestroy() {
        running = false
        scope.cancel()
        wake?.release()
        wakeLockHeld = false
        super.onDestroy()
    }

    private fun finish() {
        wake?.release()
        wakeLockHeld = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "model-download"
        const val NOTIFICATION_ID = 2
        private const val UPDATE_MILLISECONDS = 1_000L
        private const val QUIET_MILLISECONDS = 1_000L

        @Volatile var running = false
            private set

        @Volatile var wakeLockHeld = false
            private set

        /** False when Android refuses to start it from here; the download then goes on without. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, ModelDownloadService::class.java))
            true
        } catch (error: IllegalStateException) {
            false
        } catch (error: SecurityException) {
            false
        }

        internal fun notification(context: Context, state: PhoneModelSwitch): Notification {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("הורדת מודל", "Model download"), NotificationManager.IMPORTANCE_LOW),
            )
            val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(state.modelName() ?: "Ozen")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
            when (state) {
                is PhoneModelSwitch.Downloading -> {
                    val percent = (state.fraction * 100).toInt()
                    if (state.checking) {
                        builder.setContentText(tr("בודקים את הקובץ…", "Checking the file…")).setProgress(0, 0, true)
                    } else {
                        builder.setContentText(tr("מורידים… %1%", "Downloading… %1%", listOf("$percent"))).setProgress(100, percent, false)
                    }
                }
                is PhoneModelSwitch.Retrying -> builder.setContentText(tr("ההורדה נעצרה. מנסים שוב אוטומטית.", "The download stopped. Trying again on its own."))
                is PhoneModelSwitch.WaitingForWiFi -> builder.setContentText(tr("ממתינים ל־Wi‑Fi כדי להוריד. בהגדרות אפשר לאשר הורדה גם בחבילת הגלישה.", "Waiting for Wi‑Fi to download it. Downloads on mobile data can be allowed in Settings."))
                is PhoneModelSwitch.Offline -> builder.setContentText(tr("אין חיבור לאינטרנט, ולכן אי אפשר להוריד.", "There is no internet connection to download it."))
                else -> Unit
            }
            return builder.build()
        }

        private fun PhoneModelSwitch.modelName(): String? = when (this) {
            is PhoneModelSwitch.Downloading -> file
            is PhoneModelSwitch.Retrying -> file
            is PhoneModelSwitch.WaitingForWiFi -> file
            is PhoneModelSwitch.Offline -> file
            else -> null
        }?.let { file -> PhoneModelCatalog.byFile(file)?.displayName }
    }
}

// A download that lost Wi-Fi or the connection on the way waits with the
// service still up: the network callback that resumes it only reaches a
// process Android hasn't frozen or ended.
private val PhoneModelSwitch.keepsServiceUp: Boolean
    get() = this is PhoneModelSwitch.Downloading || this is PhoneModelSwitch.Retrying ||
        this is PhoneModelSwitch.WaitingForWiFi || this is PhoneModelSwitch.Offline
