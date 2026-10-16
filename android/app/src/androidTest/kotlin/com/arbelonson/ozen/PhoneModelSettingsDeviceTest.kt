package com.arbelonson.ozen

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneSpeed
import com.arbelonson.ozen.core.UILanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneModelSettingsDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication
    private val before = app.settings.current.value.copy()
    private val modelFiles = ModelFilesAside(app.filesDir)

    @Before
    fun nothingOnThePhoneAndNothingChosen() {
        modelFiles.hold()
        app.settings.change {
            it.languageCode = "he"
            it.phoneModelFile = null
            it.phoneSpeed = null
        }
    }

    @After
    fun putBack() {
        modelFiles.putBack()
        app.settings.change {
            it.languageCode = before.languageCode
            it.phoneModelFile = before.phoneModelFile
            it.phoneSpeed = before.phoneSpeed
            it.whisperModelVariant = before.whisperModelVariant
        }
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.0")).close()
    }

    private fun openSection() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Speech model on the phone").performScrollTo().assertExists()
    }

    @Test
    fun settingsListsTheModelsOfTheSpokenLanguageRecommendedFirstAndAChoiceSurvivesTurningThePhone() {
        openSection()
        compose.onNodeWithTag("phone-model-ggml-ozen-a3-q5_0.bin").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Recommended").performScrollTo().assertExists()
        compose.onNodeWithTag("phone-model-ggml-base-q5_1.bin").performScrollTo().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.phoneModelFile == "ggml-base-q5_1.bin" }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Speech model on the phone").performScrollTo().assertExists()
        compose.onNodeWithTag("phone-model-ggml-base-q5_1.bin").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("phone-model-ggml-ozen-a3-q5_0.bin").performScrollTo().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.phoneModelFile == null }
    }

    @Test
    fun anotherLanguageIsNotOfferedTheHebrewOnlyModels() {
        app.settings.change { it.languageCode = "en" }
        openSection()
        compose.onNodeWithTag("phone-model-ggml-large-v3-turbo-q5_0.bin").performScrollTo().assertIsSelected()
        assertTrue(compose.onAllNodesWithText("Hebrew only").fetchSemanticsNodes().isEmpty())
        assertTrue(PhoneModelCatalog.offered(UILanguage.English).none { it.isHebrewOnly })
    }

    @Test
    fun testingThePhoneShowsProgressSavesTheSpeedAndRatesEveryModel() {
        openSection()
        compose.onNodeWithTag("phone-test").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Testing this phone", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(40_000) { app.settings.current.value.phoneSpeed != null }
        val speed: PhoneSpeed = app.settings.current.value.phoneSpeed!!
        assertTrue(speed.gflops > 0.5)
        compose.onNodeWithText("Test this phone again").performScrollTo().assertExists()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Recommended for this phone").fetchSemanticsNodes().size == 1 }
        val rated = listOf("Keeps up", "A little behind", "Too slow on this phone")
            .sumOf { compose.onAllNodesWithText(it).fetchSemanticsNodes().size }
        assertEquals(PhoneModelCatalog.offered(UILanguage.Hebrew).size, rated)
        assertNull(app.settings.current.value.phoneModelFile)
    }

    @Test
    fun atTheLargestTextTheListStaysReachableAndTurningSidewaysKeepsTheChoice() {
        val shell = { command: String -> ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
        try {
            shell("settings put system font_scale 2.0")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale == 2f }
            openSection()
            compose.onNodeWithTag("phone-model-ggml-small-q5_1.bin").performScrollTo().performClick()
            compose.waitUntil(5_000) { app.settings.current.value.phoneModelFile == "ggml-small-q5_1.bin" }
            instrumentation.uiAutomation.setRotation(android.view.Surface.ROTATION_90)
            compose.waitForIdle()
            compose.onNodeWithTag("phone-model-ggml-small-q5_1.bin").performScrollTo().assertIsSelected()
            assertNotNull(app.settings.current.value.phoneModelFile)
        } finally {
            instrumentation.uiAutomation.setRotation(android.view.Surface.ROTATION_0)
            shell("settings put system font_scale 1.0")
        }
    }
}
