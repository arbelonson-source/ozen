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
import com.arbelonson.ozen.core.LockScreenCaptionContent
import com.arbelonson.ozen.core.LockScreenCaptions
import com.arbelonson.ozen.core.LockScreenTextSize
import com.arbelonson.ozen.core.LockScreenUpdateThrottle
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ListeningService : Service() {
    private val captions get() = (application as OzenApplication).captions
    private val settings get() = (application as OzenApplication).settings
    private val scope = MainScope()
    private var watching: Job? = null
    private val throttle = LockScreenUpdateThrottle()
    private var flush: Job? = null
    private var drawnPublic: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            captions.pause()
            finish()
            return START_NOT_STICKY
        }
        val content = content()
        val public = onLockScreen()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(this, content, public), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification(this, content, public))
        }
        drawn(content, public)
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

    private fun onLockScreen() = settings.current.value.display.lockScreenCaptions

    /**
     * What the lock screen shows, as on the iPhone: the newest two lines
     * while captions run, one under any other status, the newest alone with
     * its age once it is a minute old, and none after a quarter of an hour.
     * The status is always the title here, so a new one goes at once.
     */
    private fun content(): LockScreenCaptionContent {
        val screen = CaptionState.screen.value
        val display = settings.current.value.display
        val textSize = LockScreenTextSize.of(display.fontSize)
        val status = screen.status.title.takeUnless { screen.captionsRunning }
        return LockScreenCaptions.content(status, now = secondsNow(), textSize) { CaptionState.lockScreenLines(it, textSize, display.showSpeakerNames) }
            .copy(status = screen.status.title)
    }

    private fun drawn(content: LockScreenCaptionContent, public: Boolean) {
        throttle.sent(content, secondsNow())
        drawnPublic = public
    }

    /**
     * Every word, status and setting brings the notification up to date, at
     * most once a second with the last change never dropped; a quiet room is
     * looked at again every half minute, so the age moves on and the lines
     * go after a quarter of an hour. Turning the lock screen captions on or
     * off goes at once: a line left on the locked phone she just hid them
     * from is what she switched off.
     */
    private suspend fun watch() = coroutineScope {
        launch {
            while (true) {
                delay(KEEP_ALIVE_MILLISECONDS)
                refresh()
            }
        }
        CaptionState.screen.combine(settings.current) { _, _ -> }.collect { refresh() }
    }

    /**
     * Captions stopped or paused by her end the notification. Paused while
     * the phone talks does not, as on the iPhone's lock screen: captions come
     * back by themselves, and Android gives an app out of sight the
     * microphone only while this notification is up.
     */
    private fun refresh() {
        val screen = CaptionState.screen.value
        if (screen.phase == PipelinePhase.Idle || (screen.phase == PipelinePhase.Paused && !screen.pausedForSpeech)) {
            finish()
            return
        }
        val content = content()
        val public = onLockScreen()
        val decision = if (public != drawnPublic) LockScreenUpdateThrottle.Decision.Send else throttle.decide(content, secondsNow())
        when (decision) {
            LockScreenUpdateThrottle.Decision.NothingNew -> Unit
            LockScreenUpdateThrottle.Decision.Send -> {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this, content, public))
                drawn(content, public)
            }
            is LockScreenUpdateThrottle.Decision.Wait -> if (flush == null) {
                flush = scope.launch {
                    delay((decision.seconds * 1_000).toLong())
                    flush = null
                    refresh()
                }
            }
        }
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.arbelonson.ozen.PAUSE"
        private const val KEEP_ALIVE_MILLISECONDS = 30_000L

        fun start(context: Context) = context.startForegroundService(Intent(context, ListeningService::class.java))

        /** The listening notification showing [content], its lines on the lock screen only when [public]. */
        internal fun notification(context: Context, content: LockScreenCaptionContent, public: Boolean): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("מקשיב", "Listening"), NotificationManager.IMPORTANCE_LOW),
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_listening)
                .setContentTitle(content.status)
                .setContentText(ListeningNotice.glance(content))
                .setSubText(content.ageNote)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            ListeningNotice.opened(content)?.let { builder.setStyle(Notification.BigTextStyle().bigText(it)) }
            // On the lock screen the lines show only while she wants captions
            // there, as on the iPhone: anyone looking at the phone can read
            // them. Otherwise the locked phone shows what Ozen is doing.
            if (public) {
                builder.setVisibility(Notification.VISIBILITY_PUBLIC)
            } else {
                builder.setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(
                    Notification.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_listening).setContentTitle(content.status).build(),
                )
            }
            return builder.build()
        }

        fun pause(context: Context) {
            context.startService(Intent(context, ListeningService::class.java).setAction(ACTION_PAUSE))
        }
    }
}

private fun secondsNow() = System.currentTimeMillis() / 1_000.0
