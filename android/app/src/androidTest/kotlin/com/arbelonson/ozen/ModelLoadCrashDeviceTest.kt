package com.arbelonson.ozen

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.whisper.PreferencesMarkerStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
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
    private val modelFiles = ModelFilesAside(app.filesDir)

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

    @Test
    fun aScreenReaderHearsTheCardAndItsChoicesAreButtons() {
        val isOnBefore = ScreenReader.isOn
        val announceBefore = ScreenReader.announce
        val heard = CopyOnWriteArrayList<String>()
        try {
            ScreenReader.isOn = { true }
            ScreenReader.announce = { _, text -> heard += text }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("model-load-crash").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { heard.any { it.startsWith("Ozen closed while setting up the speech model.") && "is lighter" in it } }
            compose.onNodeWithText("Ozen closed while setting up the speech model").assert(isHeading())
            for (tag in listOf("model-load-crash-retry", "model-load-crash-switch")) {
                compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            }
        } finally {
            ScreenReader.isOn = isOnBefore
            ScreenReader.announce = announceBefore
        }
    }

    @Test
    fun switchingOfflineKeepsTheCardSayingWhyAndTheCaptionsStartOnceTheLighterModelIsIn() {
        val networkBefore = app.captions.networkNow
        val freeBefore = app.captions.freeSpace
        val heavyFile = File(app.filesDir, heavy)
        val standIn = !heavyFile.exists()
        try {
            // The model that crashed has to be on the phone, or Switch only
            // changes the choice and nothing needs downloading.
            if (standIn) heavyFile.writeText("stands in for the model that crashed")
            modelFiles.hold(keep = heavy)
            app.captions.freeSpace = { 8_000_000_000L }
            app.captions.networkNow = { NetworkConditions.offline }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("model-load-crash").fetchSemanticsNodes().isNotEmpty() }
            val lighter = requireNotNull(app.captions.loadCrash.showing.value).file
            compose.onNodeWithTag("model-load-crash-switch").performClick()
            compose.waitUntil(5_000) { app.captions.phoneModels.switching.value is PhoneModelSwitch.Offline }
            compose.waitForIdle()
            compose.onNodeWithTag("model-load-crash").assertExists()
            compose.onNodeWithTag("model-load-crash-status").assert(hasText("There is no internet connection to download it."))
            assertEquals(PipelinePhase.Idle, app.captions.pipeline.phase)

            modelFiles.putBack()
            app.captions.networkNow = { NetworkConditions.wifi }
            app.captions.phoneModels.resumeWaiting()
            compose.waitUntil(10_000) { app.captions.pipeline.phase != PipelinePhase.Idle }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("model-load-crash").fetchSemanticsNodes().isEmpty() }
            assertEquals(lighter, app.settings.current.value.phoneModel.file)
            assertEquals(PhoneModelSwitch.Idle, app.captions.phoneModels.switching.value)
        } finally {
            app.captions.networkNow = networkBefore
            app.captions.freeSpace = freeBefore
            modelFiles.putBack()
            if (standIn) heavyFile.delete()
        }
    }

    @Test
    fun startingTheCaptionsAnotherWayTakesTheCardAwayEvenWhenTheModelIsNotThere() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("model-load-crash").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { app.captions.listen() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("model-load-crash").fetchSemanticsNodes().isEmpty() }
    }
}
