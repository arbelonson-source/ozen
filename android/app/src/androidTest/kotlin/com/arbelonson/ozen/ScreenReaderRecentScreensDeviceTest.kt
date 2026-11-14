package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.DisplayPreferences.Theme
import com.arbelonson.ozen.core.BackupModelStatus
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.UILanguage
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ScreenReaderRecentScreensDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val readerBefore = ScreenReader.isOn
    private val announceBefore = ScreenReader.announce
    private val announced = CopyOnWriteArrayList<String>()
    private val look = CaptionTheme.of(Theme.Dark, systemDark = true)

    @Before
    fun aScreenReaderThatWritesDownWhatItIsTold() {
        ScreenReader.isOn = { true }
        ScreenReader.announce = { _, text -> announced += text }
    }

    @After
    fun putBack() {
        ScreenReader.isOn = readerBefore
        ScreenReader.announce = announceBefore
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { Column { content() } } }
    }

    @Test
    fun theMicrophoneDropBannerKeepsItsCloseButtonAsAControlOfItsOwn() {
        var chosen = 0
        var dismissed = 0
        show { MicrophoneDropBanner("The microphone dropped", "Pick another one", { chosen += 1 }, { dismissed += 1 }) }
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, chosen)
        compose.onNodeWithText("The microphone dropped").performClick()
        assertEquals(1, chosen)
        compose.onNodeWithText("The microphone dropped")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun aQuickPhraseAddedOnTheSayScreenIsSaidToTheScreenReaderOnce() {
        val phrases = mutableStateOf(listOf("Hello"))
        show {
            SayScreen(
                draft = "", onDraft = {}, speaking = false, phrases = phrases.value, canSay = { true }, onSpeak = { true }, onStop = {},
                onAddPhrase = { phrases.value = phrases.value + it }, onRemovePhrase = {}, onMovePhrase = { _, _ -> }, onRestorePhrases = {},
                phrasesAreDefault = false, look = look, captionSize = 30.0, onClose = {},
            )
        }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("New phrase").performTextInput("Please write it down")
        compose.onNodeWithContentDescription("Add").performClick()
        compose.waitUntil(5_000) { "Please write it down" in phrases.value }
        compose.waitUntil(5_000) { announced.isNotEmpty() }
        assertEquals(listOf("Added: Please write it down"), announced.toList())
    }

    @Test
    fun anImportantWordAddedIsSaidToTheScreenReaderOnceAndTheAlreadyListedNoteIsPolite() {
        var alerts by mutableStateOf(emptyList<KeywordAlert>())
        show {
            ImportantWordsScreen(alerts, emptyList(), "en", UILanguage.English, emptyList(), onChange = { alerts = it }, onClose = {})
        }
        compose.onNodeWithText("A word or name, e.g., grandma").performTextInput("medicine")
        compose.onNodeWithContentDescription("Add").performClick()
        compose.waitUntil(5_000) { alerts.size == 1 }
        compose.waitUntil(5_000) { announced.isNotEmpty() }
        assertEquals(listOf("Added: medicine"), announced.toList())
        compose.onNodeWithText("A word or name, e.g., grandma").performTextInput("medicine")
        compose.onNodeWithText("“medicine” is already in the list.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun aWordAddedOnTheNamesAndWordsScreenIsSaidToTheScreenReaderOnce() {
        var words by mutableStateOf(emptyList<String>())
        show { WordsScreen(words, emptyList(), onChange = { words = it }, wordFixes = emptyList(), onWordFixes = {}, onClose = {}) }
        compose.onNodeWithText("Name or word").performTextInput("Rachel")
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(5_000) { words == listOf("Rachel") }
        compose.waitUntil(5_000) { announced.isNotEmpty() }
        assertEquals(listOf("Added: Rachel"), announced.toList())
    }

    @Test
    fun theBackupStatusInSettingsIsSaidWhenItChangesButNotWhenItFirstAppearsOrWhileDownloading() {
        val status = mutableStateOf<BackupModelStatus>(BackupModelStatus.Missing(575))
        show { PhoneBackupRow(status.value, onDownload = {}) }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), announced.toList())
        status.value = BackupModelStatus.Downloading(0.1)
        compose.waitUntil(5_000) { announced.size == 1 }
        status.value = BackupModelStatus.Downloading(0.5)
        compose.waitForIdle()
        status.value = BackupModelStatus.Ready
        compose.waitUntil(5_000) { announced.size == 2 }
        assertEquals(
            listOf("Downloading the backup", "Backup on the phone is ready: when the computer can’t be reached, the phone carries on by itself."),
            announced.toList(),
        )
    }

    @Test
    fun theBannerCardsTextButtonsAreAtLeastAFingerWide() {
        val model = PhoneModelCatalog.all.first()
        val lighter = PhoneModelCatalog.all.last()
        show {
            ListeningCutCard("Listening stopped at noon", onDismiss = {})
            LighterModelOffer(model, waiting = false, inUseBytes = null, onSwitch = {}, onDismiss = {})
            ModelLoadCrashCard(model, lighter, inUseBytes = null, switching = PhoneModelSwitch.Idle, onSwitch = {}, onRetry = {})
        }
        for (tag in listOf("listening-cut-dismiss", "model-fit-switch", "model-load-crash-retry", "model-load-crash-switch")) {
            val touch = compose.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
            val fingerSize = compose.activity.resources.displayMetrics.density * 48
            assertTrue("$tag is ${touch.width} px wide, a finger needs $fingerSize", touch.width >= fingerSize - 1)
        }
    }

    @Test
    fun theNightChecklistsTestAlertButtonSaysWhatToDoNextToTheScreenReader() {
        show {
            var sent by remember { mutableStateOf(false) }
            TestAlertButton(sent) { sent = true }
        }
        compose.onNode(hasText("Test that an alert arrives when the phone is locked")).performClick()
        compose.waitUntil(5_000) { announced.isNotEmpty() }
        assertEquals(listOf("Sent. Lock the phone, and it will arrive within 10 seconds"), announced.toList())
    }
}
