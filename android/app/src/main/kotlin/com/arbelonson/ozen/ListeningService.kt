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
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ListeningService : Service() {
    private val captions get() = (application as OzenApplication).captions
    private val scope = MainScope()
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            captions.pause()
            finish()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        captions.listen()
        if (watching == null) watching = scope.launch { watch() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        val phase = captions.pipeline.phase
        if (phase != PipelinePhase.Idle && phase != PipelinePhase.Paused) captions.pause()
        super.onDestroy()
    }

    private suspend fun watch() {
        val manager = getSystemService(NotificationManager::class.java)
        CaptionState.screen
            .map { screen -> Triple(screen.phase, screen.status.title, screen.lines.lastOrNull { it.isFinal }?.text) }
            .combine((application as OzenApplication).settings.current.map { it.display.lockScreenCaptions }) { shown, onLockScreen -> shown to onLockScreen }
            .distinctUntilChanged()
            .drop(1)
            .collect { (shown, _) ->
                val phase = shown.first
                if (phase == PipelinePhase.Idle || phase == PipelinePhase.Paused) {
                    finish()
                } else {
                    manager.notify(NOTIFICATION_ID, notification())
                }
            }
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("מקשיב", "Listening"), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val screen = CaptionState.screen.value
        val newest = screen.lines.lastOrNull { it.isFinal }?.text
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle(screen.status.title)
            .setContentText(newest ?: tr("הכתוביות יופיעו כאן.", "Captions will appear here."))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // On the lock screen the newest line shows only while she wants
        // captions there, as on the iPhone: anyone looking at the phone can
        // read it. Otherwise the locked phone shows what Ozen is doing.
        if ((application as OzenApplication).settings.current.value.display.lockScreenCaptions) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        } else {
            builder.setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(
                Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_listening).setContentTitle(screen.status.title).build(),
            )
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.arbelonson.ozen.PAUSE"

        fun start(context: Context) = context.startForegroundService(Intent(context, ListeningService::class.java))

        fun pause(context: Context) {
            context.startService(Intent(context, ListeningService::class.java).setAction(ACTION_PAUSE))
        }
    }
}
