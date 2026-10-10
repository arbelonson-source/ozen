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
import com.arbelonson.ozen.core.tr

class ListeningService : Service() {
    private var capture: MicrophoneCapture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            stopListening(ListeningPhase.Paused)
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        if (capture == null) {
            val microphone = MicrophoneCapture { }
            if (microphone.start()) {
                capture = microphone
                CaptionState.setPhase(ListeningPhase.Listening)
            } else {
                stopListening(ListeningPhase.NoMicrophone)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        capture?.stop()
        capture = null
        super.onDestroy()
    }

    private fun stopListening(phase: ListeningPhase) {
        capture?.stop()
        capture = null
        CaptionState.setPhase(phase)
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
        val newest = CaptionState.screen.value.lines.lastOrNull()?.text
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle(tr("מקשיב", "Listening"))
            .setContentText(newest ?: tr("הכתוביות יופיעו כאן.", "Captions will appear here."))
            .setContentIntent(open)
            .setOngoing(true)
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
