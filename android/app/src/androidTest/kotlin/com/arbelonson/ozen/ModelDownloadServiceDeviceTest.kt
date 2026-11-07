package com.arbelonson.ozen

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.ReleaseFileFetching
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDownloadServiceDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication
    private val modelFiles = ModelFilesAside(app.filesDir)
    private val inUse = File(app.filesDir, "ggml-ozen-a3-q5_0.bin")
    private val target = PhoneModelCatalog.all.first { it.file == "ggml-base-q5_1.bin" }
    private val networkBefore = app.captions.networkNow
    private val fetcherBefore = app.captions.modelFetcher
    private val before = app.settings.current.value.copy()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)

    private val held = object : ReleaseFileFetching {
        override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
            started.countDown()
            assertTrue("the test never released the download", release.await(60, TimeUnit.SECONDS))
            RandomAccessFile(appendingTo, "rw").use { it.setLength(target.bytes) }
            received(target.bytes - from)
        }

        override fun sha256(file: File): String = target.source.sha256
    }

    @Before
    fun aModelIsInUseAndTheNextOneIsAboutToDownload() {
        modelFiles.hold()
        inUse.writeBytes(ByteArray(10))
        app.settings.change {
            it.languageCode = "he"
            it.phoneModelFile = null
            it.phoneSpeed = null
        }
        runCatching { instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS) }
        app.captions.networkNow = { NetworkConditions.wifi }
        app.captions.modelFetcher = held
    }

    @After
    fun putBack() {
        release.countDown()
        app.captions.networkNow = networkBefore
        app.captions.modelFetcher = fetcherBefore
        File(app.filesDir, target.file).delete()
        File(app.filesDir, target.file + ".part").delete()
        inUse.delete()
        modelFiles.putBack()
        app.settings.change {
            it.languageCode = before.languageCode
            it.phoneModelFile = before.phoneModelFile
            it.phoneSpeed = before.phoneSpeed
            it.whisperModelVariant = before.whisperModelVariant
        }
    }

    private fun shown(): Notification? =
        app.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == ModelDownloadService.NOTIFICATION_ID }?.notification

    @Suppress("DEPRECATION")
    private fun runningInTheForeground(): Boolean =
        app.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .any { it.service.className == ModelDownloadService::class.java.name && it.foreground }

    @Test
    fun aDownloadChosenInSettingsRunsAForegroundServiceWithItsNotificationUntilTheModelIsThere() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Speech model on the phone").performScrollTo()
        compose.onNodeWithTag("phone-model-" + target.file).performScrollTo().performClick()
        assertTrue("the download never began", started.await(10, TimeUnit.SECONDS))
        // Android may hold a new foreground service's notification back for
        // up to 10 s (once every few minutes per app).
        compose.waitUntil(15_000) { runningInTheForeground() && shown() != null }
        val notification = shown()!!
        assertEquals(target.displayName, notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertNotNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(ModelDownloadService.CHANNEL_ID, notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, app.getSystemService(NotificationManager::class.java).getNotificationChannel(ModelDownloadService.CHANNEL_ID).importance)
        release.countDown()
        compose.waitUntil(15_000) { app.settings.current.value.phoneModelFile == target.file }
        compose.waitUntil(10_000) { !ModelDownloadService.running && shown() == null && !runningInTheForeground() }
    }

    @Test
    fun aDownloadThatLosesWiFiKeepsTheServiceUpAndFinishesWhenWiFiIsBack() {
        val cellularBefore = app.settings.current.value.allowCellularModelDownload
        try {
            app.settings.change { it.allowCellularModelDownload = false }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Speech model on the phone").performScrollTo()
            compose.onNodeWithTag("phone-model-" + target.file).performScrollTo().performClick()
            assertTrue("the download never began", started.await(10, TimeUnit.SECONDS))
            app.captions.networkNow = { NetworkConditions.cellular }
            release.countDown()
            compose.waitUntil(10_000) { app.captions.phoneModels.switching.value is PhoneModelSwitch.WaitingForWiFi }
            Thread.sleep(3_000)
            assertTrue("the service went while the download waited for Wi-Fi", ModelDownloadService.running)
            compose.waitUntil(15_000) { runningInTheForeground() && shown()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.startsWith("Waiting for Wi") == true }

            app.captions.networkNow = { NetworkConditions.wifi }
            compose.runOnUiThread { app.captions.phoneModels.resumeWaiting() }
            compose.waitUntil(15_000) { app.settings.current.value.phoneModelFile == target.file }
            compose.waitUntil(10_000) { !ModelDownloadService.running && shown() == null && !runningInTheForeground() }
        } finally {
            app.settings.change { it.allowCellularModelDownload = cellularBefore }
        }
    }
}
