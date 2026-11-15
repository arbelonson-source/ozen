package com.arbelonson.ozen

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.AudioPermission
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.PipelineFailure
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.StoppedCaptionsNotice
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.whisper.PreferencesMarkerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceRestartDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as OzenApplication
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val before: AppSettings = app.settings.current.value.copy()
    private val heavy = "ggml-ivrit-large-v3-turbo.bin"

    @Before
    fun setUp() {
        app.settings.change {
            it.hasCompletedOnboarding = true
            it.engine = TranscriptionEngineKind.WhisperKit
            it.spokenLanguage = UILanguage.Hebrew
            it.phoneModelFile = heavy
            it.notifyWhenInBackground = true
        }
        manager.cancelAll()
        app.captions.appIsActive = false
    }

    @After
    fun restore() {
        PreferencesMarkerStore(app).clear()
        app.captions.checkLoadMarker()
        app.captions.dismissLoadCrash()
        app.settings.change {
            it.engine = before.engine
            it.spokenLanguage = before.spokenLanguage
            it.phoneModelFile = before.phoneModelFile
            it.notifyWhenInBackground = before.notifyWhenInBackground
        }
        manager.cancelAll()
    }

    private fun stoppedNotice() = manager.activeNotifications.firstOrNull { it.tag == StoppedCaptionsNotice.IDENTIFIER }

    @Test
    fun aRestartAfterTheAppWasEndedDuringModelLoadDoesNotListenAndTellsHerCaptionsStopped() {
        PreferencesMarkerStore(app).write("loading $heavy")
        app.captions.checkLoadMarker()
        assertFalse(app.captions.mayStartCaptions())
        val listen = app.captions.resumeAfterRestart()
        assertFalse(listen)
        assertEquals("the next start would not know the model didn't load", "loading $heavy", PreferencesMarkerStore(app).read())
        assertEquals(PipelinePhase.Idle, app.captions.pipeline.phase)
        val until = System.currentTimeMillis() + 5_000
        while (stoppedNotice() == null && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("no stopped-captions notice", stoppedNotice() != null)

        app.captions.appIsActive = true
        val gone = System.currentTimeMillis() + 5_000
        while (stoppedNotice() != null && System.currentTimeMillis() < gone) Thread.sleep(100)
        assertNull("the notice stayed with the app open", stoppedNotice())
        app.captions.appIsActive = false
        Thread.sleep(1_000)
        assertNull("the notice came back after the app was left", stoppedNotice())
    }

    @Test
    fun aRestartSpeaksTheChosenAppLanguageNotTheDefaultOfAFreshProcess() {
        val languageBefore = Localization.language
        app.settings.change { it.appLanguage = AppLanguage.English }
        Localization.language = UILanguage.Hebrew
        try {
            PreferencesMarkerStore(app).clear()
            app.captions.checkLoadMarker()
            assertTrue(app.captions.resumeAfterRestart())
            assertEquals(UILanguage.English, Localization.language)
        } finally {
            Localization.language = languageBefore
            app.settings.change { it.appLanguage = before.appLanguage }
        }
    }

    @Test
    fun aRestartAfterTheAppWasEndedWhileListeningListensAndPostsNoNotice() {
        PreferencesMarkerStore(app).clear()
        app.captions.checkLoadMarker()
        assertTrue(app.captions.mayStartCaptions())
        assertTrue(app.captions.resumeAfterRestart())
        assertNull(stoppedNotice())
    }

    @Test
    fun aRestartRefusedBecauseTheMicrophonePermissionWasTurnedOffSaysSoInTheNoticeAndOnTheScreen() {
        PreferencesMarkerStore(app).clear()
        app.captions.checkLoadMarker()
        val work = File(context.cacheDir, "mic-revoked-test").apply { deleteRecursively(); mkdirs() }
        val denied = object : AudioCapturing by AndroidAudioCapture(context) {
            override suspend fun requestPermission() = AudioPermission.Denied
        }
        val languageBefore = Localization.language
        Localization.language = UILanguage.English
        var session: CaptionSession? = null
        instrumentation.runOnMainSync {
            session = CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                denied,
                historyFolder = File(work, "history"),
            ).also { it.microphoneGranted = { false } }
        }
        val captions = session!!
        try {
            instrumentation.runOnMainSync { captions.foregroundRefused(SecurityException("the microphone permission is off")) }
            val until = System.currentTimeMillis() + 5_000
            while (stoppedNotice() == null && System.currentTimeMillis() < until) Thread.sleep(100)
            val body = stoppedNotice()?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
            assertEquals("Ozen doesn’t have permission to listen. Open the app to fix it.", body)

            instrumentation.runOnMainSync { captions.appIsActive = true }
            val shown = System.currentTimeMillis() + 5_000
            while (captions.pipeline.phase.failure == null && System.currentTimeMillis() < shown) Thread.sleep(100)
            assertEquals(PipelineFailure.Kind.MicrophonePermissionDenied, captions.pipeline.phase.failure?.kind)
            assertEquals("No microphone access", CaptionState.screen.value.status.title)
        } finally {
            Localization.language = languageBefore
            instrumentation.runOnMainSync {
                captions.appIsActive = false
                captions.close()
            }
        }
    }

    private fun withMicrophoneOffSession(granted: Boolean = false, body: (CaptionSession) -> Unit) {
        PreferencesMarkerStore(app).clear()
        app.captions.checkLoadMarker()
        val work = File(context.cacheDir, "mic-off-once-test").apply { deleteRecursively(); mkdirs() }
        val denied = object : AudioCapturing by AndroidAudioCapture(context) {
            override suspend fun requestPermission() = AudioPermission.Denied
        }
        val languageBefore = Localization.language
        Localization.language = UILanguage.English
        var session: CaptionSession? = null
        instrumentation.runOnMainSync {
            session = CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                denied,
                historyFolder = File(work, "history"),
            ).also { it.microphoneGranted = { granted } }
        }
        val captions = session!!
        try {
            body(captions)
        } finally {
            Localization.language = languageBefore
            instrumentation.runOnMainSync {
                captions.appIsActive = false
                captions.close()
            }
        }
    }

    private fun waitUntil(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    @Test
    fun theMicrophoneNoticeIsNotPostedASecondTimeAfterSheOpenedTheAppAndSawTheRedScreen() {
        withMicrophoneOffSession { captions ->
            instrumentation.runOnMainSync { captions.foregroundRefused(SecurityException("the microphone permission is off")) }
            waitUntil { stoppedNotice() != null }
            assertTrue(stoppedNotice() != null)

            instrumentation.runOnMainSync { captions.appIsActive = true }
            waitUntil { captions.pipeline.phase.failure != null }
            assertEquals(PipelineFailure.Kind.MicrophonePermissionDenied, captions.pipeline.phase.failure?.kind)
            manager.cancelAll()

            instrumentation.runOnMainSync { captions.appIsActive = false }
            Thread.sleep(2_000)
            assertNull(stoppedNotice())
        }
    }

    @Test
    fun aRefusalThatArrivesWhileTheAppIsOnScreenLeavesNoNoticeWhenSheLeaves() {
        withMicrophoneOffSession(granted = true) { captions ->
            instrumentation.runOnMainSync { captions.appIsActive = true }
            instrumentation.runOnMainSync { captions.foregroundRefused(SecurityException("the phone refused the service")) }
            Thread.sleep(1_000)
            assertNull(stoppedNotice())

            instrumentation.runOnMainSync { captions.appIsActive = false }
            Thread.sleep(2_000)
            assertNull(stoppedNotice())
        }
    }
}
