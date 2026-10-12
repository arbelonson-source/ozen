package com.arbelonson.ozen

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.BackupModelStatus
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
    fun theScreenStaysOnWhileTheBackupModelDownloadsWithTheCaptionsStoppedAsOnTheIPhone() {
        assumeFalse("the English model is already on this phone", File(app.filesDir, WhisperModelCatalog.phoneModelFile(UILanguage.English)).exists())
        val before = app.settings.current.value.copy()
        val fetcherBefore = app.captions.modelFetcher
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
                    throw IOException("ended by the test")
                }

                override fun sha256(file: File): String = ""
            }
            show(PipelinePhase.Idle)
            compose.waitUntil(5_000) { !screenKeptOn() }
            compose.runOnUiThread { app.captions.downloadBackup() }
            compose.waitUntil(5_000) { app.captions.backupStatus.value is BackupModelStatus.Downloading }
            compose.waitUntil(5_000) { screenKeptOn() }
            release.countDown()
            compose.waitUntil(10_000) { app.captions.backupStatus.value !is BackupModelStatus.Downloading }
            compose.waitUntil(5_000) { !screenKeptOn() }
        } finally {
            release.countDown()
            app.captions.modelFetcher = fetcherBefore
            app.settings.change {
                it.engine = before.engine
                it.spokenLanguage = before.spokenLanguage
                it.allowCellularModelDownload = before.allowCellularModelDownload
            }
        }
    }
}
