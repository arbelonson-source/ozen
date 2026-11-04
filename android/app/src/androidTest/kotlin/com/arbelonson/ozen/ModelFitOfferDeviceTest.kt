package com.arbelonson.ozen

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.ModelFitWatch
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.util.UUID
import com.arbelonson.ozen.core.UILanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

import org.junit.Rule
import org.junit.Test

class ModelFitOfferDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val before: AppSettings = app.settings.current.value.copy()
    private val title = "Captions ran late on this phone"

    @After
    fun restore() {
        app.settings.change {
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

    private fun fallBehindOnTurbo() {
        app.settings.change {
            it.hasCompletedOnboarding = true
            it.engine = TranscriptionEngineKind.WhisperKit
            it.spokenLanguage = UILanguage.English
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
        fallBehindOnTurbo()
        captionsStopped()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("Small (190\u00A0MB) is lighter and keeps up better here.").assertExists()
        compose.onNodeWithTag("model-fit-switch").performClick()
        compose.waitUntil(5_000) { app.settings.current.value.phoneModelFile == "ggml-small-q5_1.bin" }
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun notNowRemembersTheModelOnThisPhoneAndTheCardDoesNotComeBack() {
        fallBehindOnTurbo()
        captionsStopped()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithContentDescription("Not now").performClick()
        val small = PhoneModelCatalog.all.first { it.file == "ggml-small-q5_1.bin" }
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
}
