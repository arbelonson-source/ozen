package com.arbelonson.ozen

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.whisper.PreferencesMarkerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ModelLoadCrashDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val before: AppSettings = app.settings.current.value.copy()
    private val heavy = "ggml-ivrit-large-v3-turbo.bin"

    @Before
    fun markTheHeavyModelAsLoadingWhenTheAppWasEnded() {
        app.settings.change {
            it.appLanguage = AppLanguage.English
            it.hasCompletedOnboarding = true
            it.engine = TranscriptionEngineKind.WhisperKit
            it.spokenLanguage = UILanguage.Hebrew
            it.phoneModelFile = heavy
        }
        PreferencesMarkerStore(app).write("loading $heavy")
        app.captions.checkLoadMarker()
        app.captions.launchHandled = false
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { Localization.language == UILanguage.English }
    }

    @After
    fun restore() {
        compose.runOnUiThread { app.captions.pipeline.stop() }
        app.captions.launchHandled = true
        app.captions.dismissLoadCrash()
        PreferencesMarkerStore(app).clear()
        app.settings.change {
            it.appLanguage = before.appLanguage
            it.hasCompletedOnboarding = before.hasCompletedOnboarding
            it.engine = before.engine
            it.spokenLanguage = before.spokenLanguage
            it.phoneModelFile = before.phoneModelFile
            it.whisperModelVariant = before.whisperModelVariant
        }
    }

    @Test
    fun aMarkedModelShowsTheCardAndKeepsTheCaptionsStoppedUntilTryAgain() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("model-load-crash").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ozen closed while setting up the speech model").assertExists()
        val until = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < until) {
            assertEquals("the captions started on their own", PipelinePhase.Idle, app.captions.pipeline.phase)
            Thread.sleep(100)
        }
        compose.onNodeWithTag("model-load-crash-switch").assertExists()
        compose.onNodeWithTag("model-load-crash-retry").performClick()
        compose.waitUntil(10_000) { app.captions.pipeline.phase != PipelinePhase.Idle }
        compose.onNodeWithTag("model-load-crash").assertDoesNotExist()
    }
}
