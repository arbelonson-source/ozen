package com.arbelonson.ozen

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.BackupModelStatus
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ReleaseFileFetching
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.WhisperModelCatalog
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepScreenOnDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication

    @After
    fun clear() {
        compose.runOnUiThread { CaptionState.clear() }
    }

    private fun show(phase: PipelinePhase) = compose.runOnUiThread {
        CaptionState.show(phase, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList(), listeningStartedAt = System.currentTimeMillis() / 1_000.0)
    }

    private fun screenKeptOn(): Boolean {
        val dump = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys window windows")
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        val window = dump.substringAfter("com.arbelonson.ozen/com.arbelonson.ozen.MainActivity}:", "").substringBefore("Window #")
        return window.lineSequence().any { it.trim().startsWith("fl=") && "KEEP_SCREEN_ON" in it }
    }

    @Test
    fun theScreenStaysOnWhileCaptionsListenAndMayTurnOffOncePaused() {
        show(PipelinePhase.Listening)
        compose.waitUntil(5_000) { screenKeptOn() }
        show(PipelinePhase.Paused)
        compose.waitUntil(5_000) { !screenKeptOn() }
    }

    @Test
    fun theScreenStaysOnWhileANewSpeakersVoiceIsRecordedSinceALockedPhoneGivesTheAppOnlySilence() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        show(PipelinePhase.Idle)
        compose.waitUntil(5_000) { !screenKeptOn() }
        try {
            compose.onAllNodesWithText("Settings").onFirst().performClick()
            compose.onNodeWithText("Add speaker").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Dana")
            val window = compose.activity.window
            compose.runOnUiThread { WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime()) }
            compose.waitUntil(5_000) { compose.runOnUiThread { ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom == 0 } }
            compose.onNodeWithText("Record and save (30 seconds)").performClick()
            compose.waitUntil(5_000) { SpeakerRecordings.progress.value != null }
            compose.waitUntil(5_000) { screenKeptOn() }
            compose.onNodeWithText("Stop without saving").performClick()
            compose.waitUntil(10_000) { SpeakerRecordings.progress.value == null }
            compose.waitUntil(5_000) { !screenKeptOn() }
        } finally {
            SpeakerRecordings.stop()
        }
    }

    @Test
    fun theScreenStaysOnWhileTheBackupModelDownloadsWithTheCaptionsStoppedAsOnTheIPhone() {
        assumeFalse("the English model is already on this phone", File(app.filesDir, WhisperModelCatalog.phoneModelFile(UILanguage.English)).exists())
        val before = app.settings.current.value.copy()
        val fetcherBefore = app.captions.modelFetcher
        val networkBefore = app.captions.networkNow
        val release = CountDownLatch(1)
        try {
            app.settings.change {
                it.engine = TranscriptionEngineKind.HomeServer
                it.spokenLanguage = UILanguage.English
                it.allowCellularModelDownload = true
            }
            app.captions.modelFetcher = object : ReleaseFileFetching {
                override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
                    release.await(30, TimeUnit.SECONDS)
                    received(1)
                    throw IOException("ended by the test")
                }

                override fun sha256(file: File): String = ""
            }
            show(PipelinePhase.Idle)
            compose.waitUntil(5_000) { !screenKeptOn() }
            compose.runOnUiThread { app.captions.downloadBackup() }
            compose.waitUntil(5_000) { app.captions.backupStatus.value is BackupModelStatus.Downloading }
            compose.waitUntil(5_000) { screenKeptOn() }
            // Ended as Wi-Fi going with cellular downloads off does, which
            // isn't counted a failure: a failed backup stays offered as "Try
            // again" for the rest of the run, and later tests look for the
            // first offer.
            app.settings.change { it.allowCellularModelDownload = false }
            app.captions.networkNow = { NetworkConditions.cellular }
            release.countDown()
            compose.waitUntil(10_000) { app.captions.backupStatus.value !is BackupModelStatus.Downloading }
            compose.waitUntil(5_000) { !screenKeptOn() }
        } finally {
            release.countDown()
            app.captions.modelFetcher = fetcherBefore
            app.captions.networkNow = networkBefore
            app.settings.change {
                it.engine = before.engine
                it.spokenLanguage = before.spokenLanguage
                it.allowCellularModelDownload = before.allowCellularModelDownload
            }
        }
    }
}
