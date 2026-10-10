package com.arbelonson.ozen

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
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

    @Before
    fun remember() {
        engineBefore = app.settings.current.value.engine
        serviceBefore = app.settings.current.value.cloudProvider
        app.cloudKeys.remove(CloudProvider.Deepgram)
    }

    @After
    fun restore() {
        app.cloudKeys.remove(CloudProvider.Deepgram)
        app.settings.change {
            it.engine = engineBefore
            it.cloudProvider = serviceBefore
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
}
