package com.arbelonson.ozen

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.TranscriptionEngineKind
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun remember() {
        engineBefore = app.settings.current.value.engine
        serviceBefore = app.settings.current.value.cloudProvider
        addressBefore = app.settings.current.value.homeServerAddress
        codeBefore = app.homeServerCode.read()
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
}
