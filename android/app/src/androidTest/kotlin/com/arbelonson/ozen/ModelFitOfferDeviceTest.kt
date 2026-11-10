package com.arbelonson.ozen

import android.os.Build
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.ByteSize
import com.arbelonson.ozen.core.PhoneModelSwitch
import java.io.File
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.ModelFitWatch
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.util.UUID
import com.arbelonson.ozen.core.UILanguage
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

import org.junit.Rule
import org.junit.Test

class ModelFitOfferDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val before: AppSettings = app.settings.current.value.copy()
    private val modelFiles = ModelFilesAside(app.filesDir)
    private val freeSpaceBefore = app.captions.freeSpace
    private val small = PhoneModelCatalog.all.first { it.file == "ggml-small-q5_1.bin" }
    private val title = "Captions ran late on this phone"

    @Before
    fun readEnglish() {
        app.settings.change { it.appLanguage = AppLanguage.English }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { Localization.language == UILanguage.English }
    }

    @After
    fun restore() {
        app.captions.freeSpace = freeSpaceBefore
        modelFiles.putBack()
        app.settings.change {
            it.appLanguage = before.appLanguage
            it.hasCompletedOnboarding = before.hasCompletedOnboarding
            it.engine = before.engine
            it.spokenLanguage = before.spokenLanguage
            it.phoneModelFile = before.phoneModelFile
            it.whisperModelVariant = before.whisperModelVariant
            it.phoneLiveMeasurement = before.phoneLiveMeasurement
            it.modelFitDismissed = before.modelFitDismissed
        }
        compose.runOnUiThread { CaptionState.clear() }
    }

    private fun fallBehindOnTurbo(spoken: UILanguage = UILanguage.English) {
        app.settings.change {
            it.hasCompletedOnboarding = true
            it.engine = TranscriptionEngineKind.WhisperKit
            it.spokenLanguage = spoken
            it.phoneModelFile = null
            it.modelFitDismissed = emptyList()
            it.phoneLiveMeasurement = ModelFitWatch.measurement(it.phoneModel, 20, 20 * 2.4, now = System.currentTimeMillis() / 1000.0, device = Build.MODEL.orEmpty())
        }
    }

    private fun captionsStopped() {
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList())
        }
    }

    @Test
    fun aModelThatFellBehindOffersTheLighterOneAndSwitchChoosesIt() {
        assertTrue("put ${small.file} in ${app.filesDir}: without it the switch starts a download the test runner blocks", File(app.filesDir, small.file).isFile)
        modelFiles.hold(keep = small.file)
        fallBehindOnTurbo()
        captionsStopped()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("Small (190\u00A0MB) is lighter and keeps up better here.").assertExists()
        compose.onNodeWithTag("model-fit-switch").performClick()
        compose.waitUntil(5_000) { app.settings.current.value.phoneModelFile == "ggml-small-q5_1.bin" }
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun aScreenReaderHearsSwitchAsAButton() {
        fallBehindOnTurbo()
        captionsStopped()
        compose.onNodeWithTag("model-fit-switch").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun notNowRemembersTheModelOnThisPhoneAndTheCardDoesNotComeBack() {
        fallBehindOnTurbo()
        captionsStopped()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithContentDescription("Not now").performClick()
        compose.waitUntil(5_000) { ModelFitWatch.dismissalKey(small.variant, Build.MODEL.orEmpty()) in app.settings.current.value.modelFitDismissed }
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(null, app.settings.current.value.phoneModelFile)
        captionsStopped()
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun noCardWhileCaptionsAreRunning() {
        fallBehindOnTurbo()
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList())
        }
        compose.onNodeWithText(title).assertDoesNotExist()
        assertTrue(app.settings.current.value.modelFitOffer(null, Build.MODEL.orEmpty()) { true } != null)
    }

    private fun lastConversation() = listOf("First line said.", "Newest line said.").mapIndexed { index, text ->
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, startTimestamp = index.toDouble(), lastUpdateTimestamp = index.toDouble())
    }

    @Test
    fun theCardShowsAfterTheNewestLineWhenTheLastConversationIsStillOnScreen() {
        fallBehindOnTurbo()
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = lastConversation())
        }
        compose.onNodeWithText("Newest line said.").assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()
        val newest = compose.onNodeWithText("Newest line said.").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("model-fit-offer").fetchSemanticsNode().boundsInRoot
        assertTrue("the card sits under the newest line", card.top >= newest.bottom)
    }

    @Test
    fun noCardWhileACallHoldsTheAudio() {
        fallBehindOnTurbo()
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = true, scheduledRetry = null, segments = emptyList())
        }
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = true, scheduledRetry = null, segments = lastConversation())
        }
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun noCardWhileCaptionsRunOverTheLastConversation() {
        fallBehindOnTurbo()
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = lastConversation())
        }
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun aLighterModelThatLackedRoomIsOfferedOnceThePhoneIsBackWithRoomToSpare() {
        modelFiles.hold()
        app.captions.freeSpace = { 0L }
        fallBehindOnTurbo()
        captionsStopped()
        compose.onNodeWithText(title).assertDoesNotExist()
        app.captions.freeSpace = { 8_000_000_000L }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        captionsStopped()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theCardSaysTheSwitchIsWaitingForWiFiInsteadOfVanishing() {
        val networkBefore = app.captions.networkNow
        val cellularBefore = before.allowCellularModelDownload
        try {
            // The running model has to be on the phone, or a switch skips
            // the download and simply changes the choice.
            val running = PhoneModelCatalog.recommended(UILanguage.Hebrew)
            assertTrue("put ${running.file} in ${app.filesDir}: without the running model the switch never waits for Wi-Fi", File(app.filesDir, running.file).isFile)
            modelFiles.hold(keep = running.file)
            app.captions.freeSpace = { 8_000_000_000L }
            app.captions.networkNow = { NetworkConditions.cellular }
            fallBehindOnTurbo(UILanguage.Hebrew)
            app.settings.change { it.allowCellularModelDownload = false }
            captionsStopped()
            compose.onNodeWithTag("model-fit-switch").performClick()
            compose.waitUntil(5_000) { app.captions.phoneModels.switching.value is PhoneModelSwitch.WaitingForWiFi }
            compose.onNodeWithTag("model-fit-waiting").assertExists()
            compose.onNodeWithTag("model-fit-switch").assertDoesNotExist()
            compose.onNodeWithText(title).assertExists()
        } finally {
            app.captions.networkNow = networkBefore
            app.settings.change { it.allowCellularModelDownload = cellularBefore }
        }
    }

    @Test
    fun theCardSaysHowMuchRoomFreeingTheModelInUseGivesBack() {
        modelFiles.hold()
        val running = File(app.filesDir, PhoneModelCatalog.recommended(UILanguage.English).file)
        running.writeBytes(ByteArray(3_000_000))
        try {
            fallBehindOnTurbo()
            captionsStopped()
            compose.onNodeWithText(title).assertExists()
            val line = "The model in use now (${ByteSize.text(3_000_000)}) is then removed from the phone to free up space."
            compose.onNodeWithText(line).assertExists()
        } finally {
            running.delete()
        }
    }

    @Test
    fun theCardSaysNothingAboutRemovalWhenTheModelInUseIsNotOnThePhone() {
        modelFiles.hold()
        fallBehindOnTurbo()
        captionsStopped()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithTag("model-replaced-line").assertDoesNotExist()
    }
}
