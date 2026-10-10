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
            .drop(1)
            .map { screen -> screen.phase to screen.lines.lastOrNull { it.isFinal }?.text }
            .distinctUntilChanged()
            .collect { (phase, _) ->
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
        val newest = CaptionState.screen.value.lines.lastOrNull { it.isFinal }?.text
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle(tr("מקשיב", "Listening"))
            .setContentText(newest ?: tr("הכתוביות יופיעו כאן.", "Captions will appear here."))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.arbelonson.ozen.PAUSE"

        fun start(context: Context) = context.startForegroundService(Intent(context, ListeningService::class.java))

        fun pause(context: Context) {
            context.startService(Intent(context, ListeningService::class.java).setAction(ACTION_PAUSE))
        }
    }
}
