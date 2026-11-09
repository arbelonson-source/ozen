package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NameAlertScreenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val before = app.settings.current.value.copy()
    private val readerBefore = ScreenReader.isOn
    private val announceBefore = ScreenReader.announce
    private val announced = CopyOnWriteArrayList<String>()
    private val otherLetters = "Captions are written in the letters of the spoken language, so a word written in other letters may never be found."

    private fun alerts() = app.settings.current.value.keywordAlerts.map { it.phrase }

    private val grandma get() = compose.onNode(hasText("Grandma") and hasClickAction())

    @Before
    fun theNameScreen() {
        ScreenReader.isOn = { true }
        ScreenReader.announce = { _, text -> announced += text }
        compose.runOnUiThread {
            app.settings.change {
                it.keywordAlerts = emptyList()
                it.vocabulary = emptyList()
                it.languageCode = "he"
            }
            compose.activity.setContent {
                val settings by app.settings.current.collectAsState()
                NameAlertScreen(settings, onAddName = app.captions::addNameAlert, onAddAlert = app.captions::addAlert, onClose = {})
            }
        }
    }

    @After
    fun putBack() {
        ScreenReader.isOn = readerBefore
        ScreenReader.announce = announceBefore
        app.settings.change {
            it.keywordAlerts = before.keywordAlerts
            it.vocabulary = before.vocabulary
            it.languageCode = before.languageCode
        }
    }

    // The field just empties and Add turns grey, so a blind person could not
    // tell the name went in, and typed it again or gave up.
    @Test
    fun aNameOrASuggestionAddedIsSaidToTheScreenReader() {
        compose.onNodeWithText("Your name").performTextInput("דנה")
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(5_000) { alerts() == listOf("דנה") }
        compose.waitUntil(5_000) { "Added: דנה" in announced }
        grandma.performScrollTo().performClick()
        compose.waitUntil(5_000) { "Added: Grandma" in announced }
        assertEquals(listOf("Added: דנה", "Added: Grandma"), announced.filter { it.startsWith("Added") })
    }

    // "Dana" in Latin letters can never be found in Hebrew captions. The
    // warning went unread, vanished on Add, and a green tick then promised a
    // vibration for it.
    @Test
    fun aNameInOtherLettersIsWarnedAboutAloudKeepsTheWarningOnceAddedAndIsNotPromisedAVibration() {
        compose.onNodeWithText(otherLetters).assertDoesNotExist()
        compose.onNodeWithText("Your name").performTextInput("Dana")
        compose.onNodeWithText(otherLetters).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(5_000) { alerts() == listOf("Dana") }
        compose.onNodeWithText(otherLetters).assertExists()
        compose.onNodeWithText("The phone will vibrate for", substring = true).assertDoesNotExist()
        grandma.performScrollTo().performClick()
        compose.waitUntil(5_000) { alerts().toSet() == setOf("Dana", "סבתא") }
        compose.onNodeWithText("✓ The phone will vibrate for: Grandma").performScrollTo().assertExists()
    }

    // The keyboard stayed up over the only line saying what will buzz.
    @Test
    fun addAndTheKeyboardsDoneBothPutTheKeyboardAway() {
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("דנה")
        field.assertIsFocused()
        field.performImeAction()
        compose.waitUntil(5_000) { alerts() == listOf("דנה") }
        field.assertIsNotFocused()
        field.performTextInput("רותי")
        field.assertIsFocused()
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(5_000) { alerts().toSet() == setOf("דנה", "רותי") }
        field.assertIsNotFocused()
    }

    // A placeholder was the field's only name, gone with the first letter.
    @Test
    fun theNameFieldKeepsItsNameOnceSomethingIsTyped() {
        compose.onNodeWithText("Your name").performTextInput("Dana")
        compose.onNode(hasSetTextAction() and hasText("Your name")).assertExists()
    }

    // "+ Grandma" and "✓ Grandma" were read out as "plus" and "check mark",
    // and nothing said the grey one was already on.
    @Test
    fun aSuggestionShowsOnlyItsWordAndSaysWhenItIsAlreadyOnTheList() {
        grandma.performScrollTo().assertIsEnabled()
        grandma.assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Grandma"))))
        grandma.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        grandma.performClick()
        compose.waitUntil(5_000) { alerts() == listOf("סבתא") }
        grandma.assertIsNotEnabled()
        grandma.assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Grandma"))))
        grandma.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Already on the list"))
    }
}
