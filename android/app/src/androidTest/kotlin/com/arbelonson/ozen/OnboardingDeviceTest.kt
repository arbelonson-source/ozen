package com.arbelonson.ozen

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication
    private val before = app.settings.current.value.copy()
    private val notificationsAllowed = AlertNotifier.allowed

    private fun alerts() = app.settings.current.value.keywordAlerts.map { it.phrase }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun screenKeptOn(): Boolean {
        val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys window windows")
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        val window = dump.substringAfter("com.arbelonson.ozen/com.arbelonson.ozen.MainActivity}:", "").substringBefore("Window #")
        return window.lineSequence().any { it.trim().startsWith("fl=") && "KEEP_SCREEN_ON" in it }
    }

    @Before
    fun firstLaunch() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        AlertNotifier.allowed = { false }
        compose.runOnUiThread {
            app.captions.launchHandled = false
            app.settings.change {
                it.keywordAlerts = emptyList()
                it.vocabulary = emptyList()
                it.engine = TranscriptionEngineKind.WhisperKit
                it.languageCode = "he"
                it.hasCompletedOnboarding = false
            }
        }
    }

    @After
    fun putBack() {
        AlertNotifier.allowed = notificationsAllowed
        compose.runOnUiThread {
            if (app.captions.pipeline.phase != PipelinePhase.Idle) app.captions.pipeline.stop()
            app.captions.launchHandled = true
        }
        app.settings.change {
            it.keywordAlerts = before.keywordAlerts
            it.vocabulary = before.vocabulary
            it.engine = before.engine
            it.languageCode = before.languageCode
            it.hasCompletedOnboarding = true
        }
    }

    // Older people often set the phone's largest text, and German words are
    // long: "Weiter" broke in the middle beside "Überspringen", and
    // "Hinzufügen" left the name field a few letters wide. The iPhone puts
    // the field above Add at its largest sizes.
    @Test
    fun atTheLargestTextInGermanEachButtonKeepsItsWordWholeAndTheNameFieldItsWidth() {
        val language = app.settings.current.value.appLanguage
        val shell = { command: String -> ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
        try {
            app.settings.change { it.appLanguage = AppLanguage.German }
            shell("settings put system font_scale 2.0")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale == 2f && shown("Weiter") }
            repeat(4) {
                compose.onNodeWithText("Weiter").performClick()
                compose.waitForIdle()
            }
            compose.onNodeWithText("Wenn Sie gerufen werden").assertExists()
            for (label in listOf("Weiter", "Überspringen", "Hinzufügen")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals("$label broke over lines", 1, layouts.single().lineCount)
            }
            val page = compose.onRoot().fetchSemanticsNode().size.width
            val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().size.width
            assertTrue("the name field was $field of $page wide", field >= page * 0.6f)
        } finally {
            shell("settings put system font_scale 1.0")
            app.settings.change { it.appLanguage = language }
        }
    }

    @Test
    fun backInTheWalkthroughGoesToThePageBeforeAndTheScreenReaderHearsWhichPageItIs() {
        val back = { InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.onBackPressedDispatcher.onBackPressed() } }
        compose.onNode(hasContentDescription("1 of 6")).assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Which language do people around you speak?").assertExists()
        compose.onNode(hasContentDescription("3 of 6")).assertExists()

        back()
        compose.waitForIdle()
        compose.onNodeWithText("How it works").assertExists()
        compose.onNode(hasContentDescription("2 of 6")).assertExists()
        back()
        compose.waitForIdle()
        compose.onNodeWithText("Live captions for conversation.").assertExists()
        assertFalse("Back left the walkthrough", compose.activity.isFinishing)
    }

    @Test
    fun aFirstLaunchWalksThroughTheIPhonesPagesKeepsHerNameAndStartBeginsTheCaptions() {
        compose.onNodeWithText("Live captions for conversation.").assertExists()
        compose.onNodeWithText("It all happens on the phone. Nothing is sent to the internet.").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("How it works").assertExists()
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithText("Which language do people around you speak?").assertExists()
        compose.onNodeWithText(UILanguage.Hebrew.spokenName).assertIsSelected()
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithText("✓ Microphone approved").assertExists()
        compose.onNodeWithText("Approve notifications").performScrollTo().performClick()
        compose.waitUntil(5_000) { shown("✓ Notifications approved") }
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithText("When you’re called").assertExists()
        compose.onNodeWithText("+ Grandma").performScrollTo().performClick()
        compose.waitUntil(5_000) { alerts() == listOf("סבתא") }
        compose.onNodeWithText("✓ Grandma").assertIsNotEnabled()
        compose.onNodeWithText("✓ The phone will vibrate for: Grandma").performScrollTo().assertExists()
        compose.onNodeWithText("Your name").performScrollTo().performTextInput("Dana")
        compose.onNodeWithText("Next").performClick()
        compose.waitUntil(5_000) { alerts() == listOf("סבתא", "Dana") }
        assertEquals("Dana", app.settings.current.value.vocabulary.first())
        assertFalse("סבתא" in app.settings.current.value.vocabulary)

        compose.onNodeWithText("The button below starts the captions. Good luck, Grandma.").assertExists()
        compose.onNodeWithText(DOWNLOAD_NOTE).assertDoesNotExist()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithText("Start").performClick()
        compose.waitUntil(5_000) { app.settings.current.value.hasCompletedOnboarding }
        compose.onNodeWithText("History").assertExists()
        compose.waitUntil(10_000) { app.captions.pipeline.phase != PipelinePhase.Idle }
    }

    @Test
    fun theWalkthroughAsksWhichLanguagePeopleAroundHerSpeakHebrewUntilSheChoosesAndSkipKeepsTheChoice() {
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Which language do people around you speak?").assertExists()
        compose.onNodeWithText("The captions are written in this language. You can change it anytime in Settings.").performScrollTo().assertExists()
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.languageCode == "en" }
        compose.onNodeWithText("English").assertIsSelected()
        compose.onNodeWithText(UILanguage.Hebrew.spokenName).performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("中文").performScrollTo().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.languageCode == "zh" }
        compose.onNodeWithText("Skip").performClick()
        compose.waitUntil(5_000) { app.settings.current.value.hasCompletedOnboarding }
        assertEquals("zh", app.settings.current.value.languageCode)
    }

    @Test
    fun theScreenStaysOnThroughTheWalkthroughAndSkipEndsItIntoTheCaptionsAsOnTheIPhone() {
        compose.waitUntil(5_000) { screenKeptOn() }
        compose.onNodeWithText("Next").performClick()
        assertEquals(PipelinePhase.Idle, app.captions.pipeline.phase)
        compose.onNodeWithText("Skip").performClick()
        compose.waitUntil(5_000) { app.settings.current.value.hasCompletedOnboarding }
        compose.onNodeWithText("History").assertExists()
        compose.waitUntil(10_000) { app.captions.pipeline.phase != PipelinePhase.Idle }
    }

    @Test
    fun theReadyPageSaysTheFirstStartDownloadsTheModelForALanguageWhoseModelTheAppDownloads() {
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.languageCode == "en" }
        repeat(3) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("The button below starts the captions. Good luck, Grandma.").assertExists()
        compose.onNodeWithText(DOWNLOAD_NOTE).assertExists()
    }

    private companion object {
        const val DOWNLOAD_NOTE = "The first time it runs, Ozen will download the language model. This takes a few minutes and shows on the screen. After that — instantly."
    }
}
