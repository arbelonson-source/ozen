package com.arbelonson.ozen

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.ReleaseFileFetching
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SavedAppleSpeechDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val before: AppSettings = app.settings.current.value.copy()
    private val networkBefore = app.captions.networkNow
    private val fetcherBefore = app.captions.modelFetcher
    private val release = CountDownLatch(1)

    @Before
    fun settingsFromAnIPhoneNameApplesRecognizer() {
        app.captions.networkNow = { NetworkConditions.wifi }
        app.captions.modelFetcher = object : ReleaseFileFetching {
            override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
                release.await(60, TimeUnit.SECONDS)
                throw java.io.IOException("the test sends no model")
            }

            override fun sha256(file: File): String = ""
        }
        app.settings.change {
            it.hasCompletedOnboarding = true
            it.engine = TranscriptionEngineKind.AppleSpeech
            it.spokenLanguage = UILanguage.English
        }
    }

    @After
    fun putBack() {
        release.countDown()
        compose.runOnUiThread { app.captions.pipeline.stop() }
        app.captions.networkNow = networkBefore
        app.captions.modelFetcher = fetcherBefore
        app.settings.change {
            it.hasCompletedOnboarding = before.hasCompletedOnboarding
            it.engine = before.engine
            it.spokenLanguage = before.spokenLanguage
        }
    }

    @Test
    fun aSavedAppleRecognizerStartsThePhonesModelNotTheBuiltInRecognition() {
        compose.runOnUiThread { app.captions.listen() }
        compose.waitUntil(10_000) { compose.runOnUiThread { app.captions.pipeline.activeEngineKind != null } }
        compose.runOnUiThread {
            assertEquals(TranscriptionEngineKind.WhisperKit, app.captions.pipeline.activeSettings?.engine)
            assertEquals(TranscriptionEngineKind.WhisperKit, app.captions.pipeline.activeEngineKind)
        }
    }
}
