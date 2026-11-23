package com.arbelonson.ozen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.arbelonson.ozen.core.LockScreenCaptionContent
import com.arbelonson.ozen.core.LockScreenCaptions
import com.arbelonson.ozen.core.LockScreenTextSize
import com.arbelonson.ozen.core.LockScreenUpdateThrottle
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.RestartingWatcher
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.errorGuardedScope
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ListeningService : Service() {
    private val captions get() = (application as OzenApplication).captions
    private val settings get() = (application as OzenApplication).settings
    private val scope = errorGuardedScope {
        flush = null
        captions.unexpectedError(it)
    }
    private var finished = false
    private val watcher = RestartingWatcher(
        scope = scope,
        now = { SystemClock.elapsedRealtime() / 1_000.0 },
        shouldRun = { running && !finished },
        onError = { captions.unexpectedError(it) },
        onGaveUp = { captions.noteWatcherGaveUp() },
        body = { watch() },
    )
    private val throttle = LockScreenUpdateThrottle()
    private var flush: Job? = null
    private var drawnPublic: Boolean? = null
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private val taps by lazy { Taps(this) }
    private var channelLanguage: UILanguage? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        finished = false
        if (intent?.action == ACTION_PAUSE) {
            captions.stopFromNotification()
            finish()
            return START_NOT_STICKY
        }
        if (intent == null && !captions.resumeAfterRestart()) {
            finish()
            return START_NOT_STICKY
        }
        val content = content()
        val public = onLockScreen()
        val sharing = intent?.action == ACTION_PHONE_SOUND
        val soundsOnly = intent?.action == ACTION_SOUNDS_ONLY
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The sharing type goes up first: Android 14 refuses to hand out
                // the projection to a service that is not yet of that type.
                val types = if (sharing || captions.phoneSoundOn) MICROPHONE_AND_SHARING else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, notice(content, public), types)
            } else {
                startForeground(NOTIFICATION_ID, notice(content, public))
            }
        } catch (error: SecurityException) {
            return foregroundRefused(error)
        } catch (error: IllegalStateException) {
            return foregroundRefused(error)
        }
        if (sharing) takePhoneSound(intent)
        drawn(content, public)
        if (soundsOnly) captions.listenForSoundsOnly() else captions.listen(afterRestart = intent == null)
        watcher.start()
        return START_STICKY
    }

    private fun foregroundRefused(error: Exception): Int {
        captions.foregroundRefused(error)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun takePhoneSound(intent: Intent?) {
        val data = intent?.let { if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_CONSENT, Intent::class.java) else @Suppress("DEPRECATION") it.getParcelableExtra(EXTRA_CONSENT) }
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (data == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            if (projection != null) captions.usePhoneSound(projection) else captions.phoneSoundRefused()
        } catch (error: SecurityException) {
            captions.phoneSoundRefused()
        }
    }

    override fun onDestroy() {
        running = false
        captions.endPhoneSound()
        captions.stopCameraLight()
        showingOnLockScreen = false
        watcher.stop()
        scope.cancel()
        val phase = captions.pipeline.phase
        if ((phase != PipelinePhase.Idle && phase != PipelinePhase.Paused) || captions.pipeline.soundsOnlyChosen) captions.stopFromNotification()
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
        val current = settings.current.value
        val display = current.display
        val textSize = LockScreenTextSize.of(display.fontSize)
        val status = screen.status.title.takeUnless { screen.captionsRunning }
        return LockScreenCaptions.content(status, now = secondsNow(), textSize) { CaptionState.lockScreenLines(it, textSize, display.showSpeakerNames, current.languageCode) }
            .copy(status = screen.status.title)
    }

    /**
     * The channel is made once, and again in a new language for its name;
     * the two taps are looked up once. Made at every word, each was a call
     * into the system.
     */
    private fun notice(content: LockScreenCaptionContent, public: Boolean): Notification {
        if (channelLanguage != content.language) {
            manager.createNotificationChannel(listeningChannel())
            channelLanguage = content.language
        }
        return notification(this, content, public, taps)
    }

    private fun drawn(content: LockScreenCaptionContent, public: Boolean) {
        throttle.sent(content, secondsNow(), linesShown = public)
        drawnPublic = public
        showingOnLockScreen = public
    }

    /**
     * Every word, status and setting brings the notification up to date, at
     * most once a second with the last change never dropped; a quiet room is
     * looked at again every half minute, so the age moves on and the lines
     * go after a quarter of an hour. Turning the lock screen captions on or
     * off goes at once: a line left on the locked phone she just hid them
     * from is what she switched off. The caption screen in front, or out of
     * it, is looked at again too.
     */
    private suspend fun watch() = coroutineScope {
        launch {
            while (true) {
                delay(REFRESH_MILLISECONDS)
                refresh()
            }
        }
        launch {
            while (true) {
                delay(ListeningRecorder.KEEP_ALIVE_MILLISECONDS)
                captions.keepListeningRecordAlive()
            }
        }
        combine(CaptionState.screen, settings.current, captions.listeningNoticeCovered) { _, _, _ -> }.collect { refresh() }
    }

    /**
     * Captions stopped or paused by her end the notification. Paused while
     * the phone talks does not, as on the iPhone's lock screen: captions come
     * back by themselves, and Android gives an app out of sight the
     * microphone only while this notification is up.
     */
    private fun refresh() {
        val screen = CaptionState.screen.value
        if (ServiceKeepAlive.ends(screen.phase, screen.pausedForSpeech, screen.soundsOnly, screen.pausedForRecording, screen.startsAgainAfterRecording)) {
            finish()
            return
        }
        val public = onLockScreen()
        // With the caption screen in front, or the lines kept off the lock
        // screen, nobody sees them: only a new status is drawn, and the lines
        // aren't worked out at every word to find that out.
        val seen = public && !captions.listeningNoticeCovered.value
        val looked = if (seen) content() else LockScreenCaptionContent(lines = emptyList(), status = screen.status.title)
        val decision = if (public != drawnPublic) LockScreenUpdateThrottle.Decision.Send else throttle.decide(looked, secondsNow(), linesShown = seen)
        when (decision) {
            LockScreenUpdateThrottle.Decision.NothingNew -> Unit
            LockScreenUpdateThrottle.Decision.Send -> {
                val content = if (public && !seen) content() else looked
                manager.notify(NOTIFICATION_ID, notice(content, public))
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
        finished = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        internal const val ACTION_PAUSE = "com.arbelonson.ozen.PAUSE"
        internal const val ACTION_PHONE_SOUND = "com.arbelonson.ozen.PHONE_SOUND"
        internal const val ACTION_SOUNDS_ONLY = "com.arbelonson.ozen.SOUNDS_ONLY"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_CONSENT = "consent"
        private const val MICROPHONE_AND_SHARING = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        private const val REFRESH_MILLISECONDS = 30_000L

        fun start(context: Context) = context.startForegroundService(Intent(context, ListeningService::class.java))

        /** Captions off for the night: the service stays up for the microphone, listening for sounds alone. */
        fun startSoundsOnly(context: Context) = context.startForegroundService(Intent(context, ListeningService::class.java).setAction(ACTION_SOUNDS_ONLY))

        /** She agreed in the system's sharing dialog: [consent] is its answer, used once to hear the phone's own sound. */
        fun startPhoneSound(context: Context, resultCode: Int, consent: Intent) = context.startForegroundService(
            Intent(context, ListeningService::class.java).setAction(ACTION_PHONE_SOUND).putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_CONSENT, consent),
        )

        /** The service is alive, paused for speech included; the speech model is kept while it is. */
        @Volatile internal var running = false

        /** Its notification is up with the captions on the lock screen, for the diagnostics report. */
        @Volatile var showingOnLockScreen = false
            private set

        /** Whether this notification, and with it the lock screen's captions, can be seen; a seam for tests. */
        var shown: (Context) -> Boolean = { context ->
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        internal fun listeningChannel() = NotificationChannel(CHANNEL_ID, tr("מאזינה", "Listening"), NotificationManager.IMPORTANCE_LOW)

        /** The notification's two taps: opening the app, and Stop. */
        internal class Taps(context: Context) {
            val open: PendingIntent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val stop: PendingIntent = PendingIntent.getService(
                context, 1, Intent(context, ListeningService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** The listening notification showing [content], its lines only when [public] (captions on the lock screen). */
        internal fun notification(context: Context, content: LockScreenCaptionContent, public: Boolean, taps: Taps = Taps(context)): Notification {
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_listening)
                .setContentTitle(content.status)
                .setContentIntent(taps.open)
                .addAction(Notification.Action.Builder(null, tr("עצירה", "Stop"), taps.stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
            // The lines are in it only while she wants captions on the lock
            // screen, as on the iPhone, where they show nowhere outside the
            // app otherwise; then the locked phone shows what Ozen is doing.
            // Marking them private would not keep them off it: a phone set to
            // show sensitive content when locked, the usual setting, shows a
            // private notification in full.
            if (public) {
                // Closed, the notification is one row and the system cuts the
                // end off a longer line: the newest words, the ones a glance
                // at the locked phone is for. The row is filled from the end
                // instead, as much as fits the phone's narrow side at its text
                // size; opened, the lines show whole.
                val screen = context.resources.configuration
                val row = ListeningNotice.rowCharacters(minOf(screen.screenWidthDp, screen.screenHeightDp), screen.fontScale)
                builder.setContentText(ListeningNotice.glance(content, row)).setSubText(content.ageNote)
                ListeningNotice.opened(content)?.let { builder.setStyle(Notification.BigTextStyle().bigText(it)) }
            }
            return builder.build()
        }

        fun pause(context: Context) {
            context.startService(Intent(context, ListeningService::class.java).setAction(ACTION_PAUSE))
        }
    }
}
