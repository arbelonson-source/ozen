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
 * is what asks Android not to; it goes when the download is over, given up
 * on, or waiting for its next try is over.
 */
class ModelDownloadService : Service() {
    private val models get() = (application as OzenApplication).captions.phoneModels
    private val scope = MainScope()
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val shown = notification(this, models.switching.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, shown, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, shown)
        }
        if (watching == null) watching = scope.launch { watch() }
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
        super.onDestroy()
    }

    private fun finish() {
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
                    builder.setContentText(tr("מורידים… %1%", "Downloading… %1%", listOf("$percent"))).setProgress(100, percent, false)
                }
                is PhoneModelSwitch.Retrying -> builder.setContentText(tr("ההורדה נעצרה. ננסה שוב אוטומטית.", "The download stopped. Trying again on its own."))
                else -> Unit
            }
            return builder.build()
        }

        private fun PhoneModelSwitch.modelName(): String? = when (this) {
            is PhoneModelSwitch.Downloading -> file
            is PhoneModelSwitch.Retrying -> file
            else -> null
        }?.let { file -> PhoneModelCatalog.byFile(file)?.displayName }
    }
}

private val PhoneModelSwitch.keepsServiceUp: Boolean
    get() = this is PhoneModelSwitch.Downloading || this is PhoneModelSwitch.Retrying
