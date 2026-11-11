package com.arbelonson.ozen

import android.Manifest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.BuiltInWhileWaiting
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ReleaseFileFetching
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BuiltInWhileWaitingDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication
    private val before: AppSettings = app.settings.current.value.copy()
    private val fetcherBefore = app.captions.modelFetcher
    private val modelFiles = ModelFilesAside(app.filesDir)
    private val release = CountDownLatch(1)

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun state() = compose.runOnUiThread {
        listOf(app.captions.builtInWhileWaitingOffer, app.captions.pipeline.phase, app.captions.pipeline.networkConditions, app.captions.pipeline.waitingModelGettingReady)
    }

    private fun assertWithin(millis: Long, what: String, check: () -> Boolean) {
        if (!within(millis, check)) fail("$what: ${state()}")
    }

    private fun assertShownWithin(millis: Long, text: String) {
        runCatching { compose.waitUntil(millis) { shown(text) } }.onFailure { throw AssertionError("never showed \"$text\": ${state()}", it) }
    }

    // Wi-Fi switched back on by the test before can still be connecting:
    // captions start only once the phone has stayed on mobile data a while.
    private fun staysOnMobileData(): Boolean {
        var since = 0L
        return within(30_000) {
            val now = SystemClock.elapsedRealtime()
            if (app.captions.pipeline.networkConditions?.isExpensive != true) since = 0L else if (since == 0L) since = now
            since != 0L && now - since >= SETTLED_MILLIS
        }
    }

    private fun onWiFi(): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun within(millis: Long, check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            if (compose.runOnUiThread(check)) return true
            Thread.sleep(100)
        }
        return false
    }

    @Before
    fun theModelIsNotOnThePhoneAndThePhoneIsOnMobileData() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        modelFiles.hold()
        app.captions.modelFetcher = object : ReleaseFileFetching {
            override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
                release.await(60, TimeUnit.SECONDS)
                throw IOException("the test sends no model")
            }

            override fun sha256(file: File): String = ""
        }
        app.settings.change {
            it.hasCompletedOnboarding = true
            it.engine = TranscriptionEngineKind.WhisperKit
            it.spokenLanguage = UILanguage.English
            it.allowCellularModelDownload = false
        }
        shell("svc wifi disable")
        assumeTrue("no mobile network on this phone once Wi-Fi is off", staysOnMobileData())
    }

    @After
    fun putBack() {
        release.countDown()
        compose.runOnUiThread { app.captions.pipeline.stop() }
        shell("svc wifi enable")
        within(20_000) { onWiFi() }
        app.captions.modelFetcher = fetcherBefore
        modelFiles.putBack()
        app.settings.change {
            it.hasCompletedOnboarding = before.hasCompletedOnboarding
            it.engine = before.engine
            it.spokenLanguage = before.spokenLanguage
            it.allowCellularModelDownload = before.allowCellularModelDownload
        }
    }

    @Test
    fun withTheModelWaitingForWiFiCaptionsNowRunOnThePhonesBuiltInRecognitionAndTheModelStaysChosen() {
        compose.runOnUiThread { app.captions.listen() }
        assertWithin(30_000, "never waited for Wi-Fi") { app.captions.pipeline.phase.failure?.engineUnavailability?.kind == EngineUnavailability.Kind.WaitingForWiFi }
        val installed = runBlocking { BuiltInSpeechEngine.installedLanguages(app) }
        assumeTrue("no English on this phone's own recognizer: $installed", installed.orEmpty().any { BuiltInRecognition.matches(it, "en") })
        assertWithin(15_000, "the built-in recognition wasn't offered") { app.captions.builtInWhileWaitingOffer == BuiltInWhileWaiting.Offer.BuiltInOrCellularDownload }
        assertShownWithin(10_000, "Tap for captions right now")

        compose.onNodeWithTag(STATUS_BUTTON).performClick()
        compose.onNode(hasText("Captions now, with the phone’s built-in recognition") and hasAnyAncestor(isDialog())).performClick()

        assertWithin(20_000, "the built-in recognition never started") {
            app.captions.pipeline.phase == PipelinePhase.Listening &&
                app.captions.pipeline.activeEngineKind == TranscriptionEngineKind.AppleSpeech
        }
        compose.runOnUiThread {
            assertTrue(app.captions.pipeline.isCoveringForCloud)
            assertEquals(EngineUnavailability.Kind.WaitingForWiFi, app.captions.pipeline.coverReason)
            assertEquals(false, app.captions.pipeline.activeSettings?.allowServerFallbackForAppleSpeech)
            assertEquals(TranscriptionEngineKind.WhisperKit, app.settings.current.value.engine)
        }
        assertShownWithin(10_000, "With the phone’s built-in recognition until the model downloads over Wi‑Fi")
    }

    private companion object {
        const val SETTLED_MILLIS = 3_000L
    }
}
