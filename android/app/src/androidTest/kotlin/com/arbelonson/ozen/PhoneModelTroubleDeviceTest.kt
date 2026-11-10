package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.UILanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneModelTroubleDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val before = app.settings.current.value.copy()

    @Before
    fun hebrewWithNothingChosen() {
        app.settings.change {
            it.languageCode = "he"
            it.phoneModelFile = null
        }
    }

    @After
    fun putBack() {
        app.settings.change {
            it.languageCode = before.languageCode
            it.phoneModelFile = before.phoneModelFile
        }
    }

    @Test
    fun aDownloadThatCannotGoOnSaysWhyUnderItsOwnRowAndTalkBackHearsIt() {
        val models = FakePhoneModels(app.settings)
        compose.runOnUiThread {
            compose.activity.setContent {
                val settings by app.settings.current.collectAsState()
                Column(Modifier.verticalScroll(rememberScrollState())) { PhoneModelPicker(settings, models, chooseBestAfterTest = false) }
            }
        }
        val offered = PhoneModelCatalog.offered(UILanguage.Hebrew)
        val file = offered[0].file
        val next = offered[1].file
        val reasons = listOf(
            PhoneModelSwitch.NotEnoughRoom(file, 321) to "There is no room to download it. Free up 321 MB more.",
            PhoneModelSwitch.WaitingForWiFi(file) to "Waiting for Wi‑Fi to download it. Downloads on mobile data can be allowed in Settings.",
            PhoneModelSwitch.Offline(file) to "There is no internet connection to download it.",
            PhoneModelSwitch.Retrying(file, 15) to "The download stopped. Trying again on its own.",
            PhoneModelSwitch.Failed(file) to "The download failed. Choose the model again to try again.",
        )
        for ((state, text) in reasons) {
            compose.runOnUiThread { models.switching.value = state }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            val lines = compose.onAllNodesWithText(text).fetchSemanticsNodes()
            assertEquals("$state", 1, lines.size)
            val line = lines.single()
            assertEquals("$state", LiveRegionMode.Polite, line.config.getOrNull(SemanticsProperties.LiveRegion))
            val row = compose.onNodeWithTag("phone-model-$file").fetchSemanticsNode()
            val below = compose.onNodeWithTag("phone-model-$next").fetchSemanticsNode()
            assertTrue("$state", line.positionInRoot.y >= row.positionInRoot.y + row.size.height - 1)
            assertTrue("$state", line.positionInRoot.y < below.positionInRoot.y)
        }
        compose.runOnUiThread { models.switching.value = PhoneModelSwitch.Downloading(file, 0.5) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Captions keep using the current model until the new one is ready.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithTag("phone-model-trouble-$file").fetchSemanticsNodes().isEmpty())
    }
}
