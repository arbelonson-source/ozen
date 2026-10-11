package com.arbelonson.ozen

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.CloudSpeech
import com.arbelonson.ozen.core.TranscriptionEngineKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private lateinit var engineBefore: TranscriptionEngineKind
    private lateinit var serviceBefore: CloudProvider
    private lateinit var addressBefore: String
    private var codeBefore: String? = null
    private var beamBefore = 0
    private lateinit var modelBefore: String

    @Before
    fun remember() {
        engineBefore = app.settings.current.value.engine
        serviceBefore = app.settings.current.value.cloudProvider
        addressBefore = app.settings.current.value.homeServerAddress
        codeBefore = app.homeServerCode.read()
        beamBefore = app.settings.current.value.homeServerBeam
        modelBefore = app.settings.current.value.cloudModel
        app.cloudKeys.remove(CloudProvider.Deepgram)
        app.homeServerCode.remove()
        app.settings.change { it.homeServerAddress = "" }
    }

    @After
    fun restore() {
        app.cloudKeys.remove(CloudProvider.Deepgram)
        codeBefore?.let { app.homeServerCode.save(it) } ?: app.homeServerCode.remove()
        app.settings.change {
            it.engine = engineBefore
            it.cloudProvider = serviceBefore
            it.homeServerAddress = addressBefore
            it.homeServerBeam = beamBefore
            it.cloudModel = modelBefore
        }
    }

    @Test
    fun aCloudKeyTypedInSettingsIsSavedForTheChosenServiceAndTheCloudBecomesTheEngine() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Cloud transcription").performClick()
        compose.onNodeWithText("Deepgram").performScrollTo().performClick()
        compose.onNodeWithText("Paste your Deepgram key here").performScrollTo().performTextInput("dg-device-test")
        compose.onNodeWithText("Paste your Deepgram key here").performImeAction()
        compose.onNodeWithText("Key saved on the phone").performScrollTo()
        assertEquals("dg-device-test", app.cloudKeys.read(CloudProvider.Deepgram))
        assertEquals(TranscriptionEngineKind.Cloud, app.settings.current.value.engine)
        assertEquals(CloudProvider.Deepgram, app.settings.current.value.cloudProvider)
    }

    @Test
    fun aComputerAddressAndPairingCodeTypedInSettingsAreSavedWithHintsForAddressesThatWillNotWork() {
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodesWithText("Home computer").onFirst().performClick()
        val address = compose.onNodeWithText("Computer address").performScrollTo()
        address.performTextInput("pc.example.net")
        compose.onNodeWithText("Outside the home network, use the address that starts with wss://").performScrollTo()
        address.performTextClearance()
        address.performTextInput("ws://bad host")
        compose.onNodeWithText("That address doesn’t look right").performScrollTo()
        address.performTextClearance()
        address.performTextInput("  ws://192.168.1.20:8765 ")
        compose.onNodeWithText("That address doesn’t look right").assertDoesNotExist()
        compose.onNodeWithText("Outside the home network, use the address that starts with wss://").assertDoesNotExist()
        address.performImeAction()
        assertEquals("ws://192.168.1.20:8765", app.settings.current.value.homeServerAddress)
        compose.onNodeWithText("The pairing code from the computer").performScrollTo().performTextInput(" device-test-code ")
        compose.onNodeWithText("The pairing code from the computer").performImeAction()
        compose.onNodeWithText("Pairing code saved on the phone").performScrollTo()
        compose.onNodeWithText("New code instead of the saved one").performScrollTo()
        assertEquals("device-test-code", app.homeServerCode.read())
        assertEquals(TranscriptionEngineKind.HomeServer, app.settings.current.value.engine)
    }

    @Test
    fun aKeyTypedButNeverSavedIsKeptWhenSettingsClose() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Cloud transcription").performClick()
        compose.onNodeWithText("Deepgram").performScrollTo().performClick()
        compose.onNodeWithText("Paste your Deepgram key here").performScrollTo().performTextInput("dg-kept-on-close")
        compose.onNodeWithText("Close").performScrollTo().performClick()
        compose.onNodeWithText("Close").assertDoesNotExist()
        assertEquals("dg-kept-on-close", app.cloudKeys.read(CloudProvider.Deepgram))
    }

    @Test
    fun anAddressAndCodeTypedButNeverSavedAreKeptWhenBackLeavesSettings() {
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodesWithText("Home computer").onFirst().performClick()
        compose.onNodeWithText("Computer address").performScrollTo().performTextInput("ws://192.168.1.21:8765")
        compose.onNodeWithText("The pairing code from the computer").performScrollTo().performTextInput("code-kept-on-back")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Close").assertDoesNotExist()
        assertEquals("ws://192.168.1.21:8765", app.settings.current.value.homeServerAddress)
        assertEquals("code-kept-on-back", app.homeServerCode.read())
    }

    @Test
    fun testingAComputerThatDoesNotAnswerSavesWhatWasTypedAndSaysSo() {
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodesWithText("Home computer").onFirst().performClick()
        compose.onNodeWithText("Computer address").performScrollTo().performTextInput("ws://10.0.2.2:9")
        compose.onNodeWithText("The pairing code from the computer").performScrollTo().performTextInput("code-for-the-test")
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("No answer from the computer", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("ws://10.0.2.2:9", app.settings.current.value.homeServerAddress)
        assertEquals("code-for-the-test", app.homeServerCode.read())
    }

    @Test
    fun theSpeedSliderSavesItsStepAndTheUsualSettingComesBackWithOneTap() {
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodesWithText("Home computer").onFirst().performClick()
        val slider = compose.onNodeWithContentDescription("Speed or accuracy on the computer")
        slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        assertEquals(3, app.settings.current.value.homeServerBeam)
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 of 7"))
        compose.onNodeWithText("Back to the usual setting").performScrollTo().performClick()
        assertEquals(5, app.settings.current.value.homeServerBeam)
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "5 of 7, usual"))
        compose.onNodeWithText("Back to the usual setting").assertDoesNotExist()
    }

    @Test
    fun deletingThePairingCodeAsksFirstThenRemovesIt() {
        app.homeServerCode.save("code-to-delete")
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodesWithText("Home computer").onFirst().performClick()
        compose.onNodeWithText("Delete code").performScrollTo().performClick()
        compose.onNodeWithText("Delete the computer’s code?").assertExists()
        compose.onNodeWithText("Until the computer’s QR code is scanned again", substring = true).assertExists()
        assertEquals("code-to-delete", app.homeServerCode.read())
        compose.onNodeWithText("Delete").performClick()
        assertNull(app.homeServerCode.read())
        compose.onNodeWithText("Pairing code saved on the phone").assertDoesNotExist()
        compose.onNodeWithText("Delete code").assertDoesNotExist()
    }

    @Test
    fun anOpenRouterModelChosenInSettingsIsSaved() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Cloud transcription").performClick()
        compose.onNodeWithText("OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("Fast (Gemini Flash Lite)").performScrollTo().performClick()
        assertEquals(CloudSpeech.FAST_MODEL, app.settings.current.value.cloudModel)
        compose.onNodeWithText("More accurate, a bit slower (Gemini Flash)").performScrollTo().performClick()
        assertEquals(CloudSpeech.ACCURATE_MODEL, app.settings.current.value.cloudModel)
    }
}
