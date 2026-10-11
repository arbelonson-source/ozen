package com.arbelonson.ozen

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.CloudSpeech
import com.arbelonson.ozen.core.ContrastRatio
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.KeywordMatch
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ProblemAudioStore
import com.arbelonson.ozen.core.ProblemSnapshot
import com.arbelonson.ozen.core.QuietHours
import com.arbelonson.ozen.core.SessionJournal
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.WhisperModelCatalog
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    private lateinit var displayBefore: DisplayPreferences
    private lateinit var wordsBefore: List<String>
    private lateinit var alertsBefore: List<KeywordAlert>
    private var awayAlertsBefore = true
    private var quietHoursBefore = QuietHours.default
    private var speechRateBefore = 0.45f

    @Before
    fun remember() {
        engineBefore = app.settings.current.value.engine
        serviceBefore = app.settings.current.value.cloudProvider
        addressBefore = app.settings.current.value.homeServerAddress
        codeBefore = app.homeServerCode.read()
        beamBefore = app.settings.current.value.homeServerBeam
        modelBefore = app.settings.current.value.cloudModel
        displayBefore = app.settings.current.value.display
        wordsBefore = app.settings.current.value.vocabulary
        alertsBefore = app.settings.current.value.keywordAlerts.map { it.copy() }
        awayAlertsBefore = app.settings.current.value.notifyWhenInBackground
        quietHoursBefore = app.settings.current.value.quietHours
        speechRateBefore = app.settings.current.value.speechRate
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
            it.display = displayBefore
            it.vocabulary = wordsBefore
            it.keywordAlerts = alertsBefore
            it.notifyWhenInBackground = awayAlertsBefore
            it.quietHours = quietHoursBefore
            it.speechRate = speechRateBefore
        }
        compose.runOnUiThread { CaptionState.clear() }
    }

    @Test
    fun aCloudKeyTypedInSettingsIsSavedForTheChosenServiceAndTheCloudBecomesTheEngine() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Cloud transcription").performScrollTo().performClick()
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
        compose.onAllNodesWithText("Home computer").onFirst().performScrollTo().performClick()
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
        compose.onNodeWithText("Cloud transcription").performScrollTo().performClick()
        compose.onNodeWithText("Deepgram").performScrollTo().performClick()
        compose.onNodeWithText("Paste your Deepgram key here").performScrollTo().performTextInput("dg-kept-on-close")
        compose.onNodeWithText("Close").performScrollTo().performClick()
        compose.onNodeWithText("Close").assertDoesNotExist()
        assertEquals("dg-kept-on-close", app.cloudKeys.read(CloudProvider.Deepgram))
    }

    @Test
    fun anAddressAndCodeTypedButNeverSavedAreKeptWhenBackLeavesSettings() {
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodesWithText("Home computer").onFirst().performScrollTo().performClick()
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
        compose.onAllNodesWithText("Home computer").onFirst().performScrollTo().performClick()
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
        compose.onAllNodesWithText("Home computer").onFirst().performScrollTo().performClick()
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
        compose.onAllNodesWithText("Home computer").onFirst().performScrollTo().performClick()
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
        compose.onNodeWithText("Cloud transcription").performScrollTo().performClick()
        compose.onNodeWithText("OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("Fast (Gemini Flash Lite)").performScrollTo().performClick()
        assertEquals(CloudSpeech.FAST_MODEL, app.settings.current.value.cloudModel)
        compose.onNodeWithText("More accurate, a bit slower (Gemini Flash)").performScrollTo().performClick()
        assertEquals(CloudSpeech.ACCURATE_MODEL, app.settings.current.value.cloudModel)
    }

    @Test
    fun theTextSizeAndBoldTextChosenInSettingsAreHowCaptionsAreDrawn() {
        app.settings.change { it.display = DisplayPreferences.default }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithContentDescription("Text size").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
        compose.waitForIdle()
        assertEquals(40.0, app.settings.current.value.display.fontSize, 0.0)
        compose.onNodeWithText("Back to the usual size").assertExists()
        compose.onNodeWithText("Bold text").performScrollTo().performClick()
        assertTrue(app.settings.current.value.display.boldText)
        compose.onNodeWithText("Close").performScrollTo().performClick()

        val line = TranscriptSegment(id = UUID.randomUUID(), text = "Drawn as chosen", isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(line))
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Drawn as chosen").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(40.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(FontWeight.Bold, layouts.single().layoutInput.style.fontWeight)
    }

    @Test
    fun theUsualTextSizeComesBackWithOneTapAndTheDisplaySwitchesAreSaved() {
        app.settings.change { it.display = DisplayPreferences.default.copy(fontSize = 50.0) }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Back to the usual size").performScrollTo().performClick()
        assertEquals(DisplayPreferences.default.fontSize, app.settings.current.value.display.fontSize, 0.0)
        compose.onNodeWithText("Back to the usual size").assertDoesNotExist()
        compose.onNodeWithText("Screen doesn’t turn off while listening").performScrollTo().performClick()
        assertFalse(app.settings.current.value.display.keepScreenAwake)
        compose.onNodeWithText("Hide the buttons while captions are running").performScrollTo().performClick()
        assertFalse(app.settings.current.value.display.autoHideControls)
        compose.onNodeWithText("Question mark next to lines", substring = true).performScrollTo().performClick()
        assertFalse(app.settings.current.value.display.markUncertainLines)
    }

    @Test
    fun theColoursChosenInSettingsAreSavedAndTheSampleLineShowsThem() {
        app.settings.change { it.display = DisplayPreferences.default }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Black on white").performScrollTo().performClick()
        assertEquals(DisplayPreferences.Theme.Light, app.settings.current.value.display.theme)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Hello grandma", substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color.Black, layouts.single().layoutInput.style.color)
        compose.onNodeWithText("Yellow on black").performScrollTo().performClick()
        assertEquals(DisplayPreferences.Theme.HighContrast, app.settings.current.value.display.theme)
    }

    @Test
    fun onBlackOnWhiteTheSavedCodeAndNoAnswerNotesStayReadable() {
        app.settings.change { it.display = DisplayPreferences.default.copy(theme = DisplayPreferences.Theme.Light) }
        app.homeServerCode.save("code-for-the-test")
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodesWithText("Home computer").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Computer address").performScrollTo().performTextInput("ws://10.0.2.2:9")
        assertReadableOnWhite("Pairing code saved on the phone")
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("No answer from the computer", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertReadableOnWhite("No answer from the computer")
    }

    @Test
    fun namesAndWordsAreAddedFirstListedOnceAndDeletedInSettings() {
        app.settings.change { it.vocabulary = emptyList() }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Names and special words").performScrollTo()
        compose.onNode(hasText("Names and special words") and hasText("Empty")).assertExists()
        compose.onNodeWithText("Names and special words").performClick()
        compose.onNodeWithText("No names yet").assertExists()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("Shira")
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(5_000) { app.settings.current.value.vocabulary == listOf("Shira") }
        field.performTextInput("Dr. Levi")
        field.performImeAction()
        compose.waitUntil(5_000) { app.settings.current.value.vocabulary == listOf("Dr. Levi", "Shira") }
        compose.onNodeWithText("2 / 200").assertExists()

        field.performTextInput("shira")
        compose.onNodeWithText("\u201CShira\u201D is already in the list.").assertExists()
        compose.onNodeWithText("Add").assertIsNotEnabled()
        field.performImeAction()
        assertEquals(listOf("Dr. Levi", "Shira"), app.settings.current.value.vocabulary)
        field.performTextClearance()

        compose.onNodeWithText("Shira").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { app.settings.current.value.vocabulary == listOf("Dr. Levi") }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Names and special words").performScrollTo()
        compose.onNode(hasText("Names and special words") and hasText("1")).assertExists()

        compose.onNodeWithText("Names and special words").performClick()
        val word = compose.onNode(hasText("Dr. Levi") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode()
        val delete = word.config[SemanticsActions.CustomActions].single { it.label == "Delete" }
        compose.runOnUiThread { delete.action() }
        compose.waitUntil(5_000) { app.settings.current.value.vocabulary.isEmpty() }
        compose.onNodeWithText("No names yet").assertExists()
    }

    @Test
    fun importantWordsAreAddedSwitchedOffAndBackOnAndDeletedAfterAskingInSettings() {
        app.settings.change { it.keywordAlerts = emptyList() }
        val phrases = { app.settings.current.value.keywordAlerts.map { it.phrase to it.isEnabled } }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Important words").performScrollTo()
        compose.onNode(hasText("Important words") and hasText("None")).assertExists()
        compose.onNodeWithText("Important words").performClick()
        compose.onNodeWithText("No words yet", substring = true).assertExists()
        compose.onNodeWithText("Add: Grandma").performClick()
        compose.waitUntil(5_000) { phrases() == listOf("סבתא" to true) }
        assertTrue(compose.onAllNodesWithText("Add: Grandma").fetchSemanticsNodes().isEmpty())

        val field = compose.onNode(hasSetTextAction())
        val otherLetters = "Captions are in Hebrew letters, so a word written in other letters may never be found."
        field.performTextInput("Shira")
        compose.onNodeWithText(otherLetters).assertExists()
        compose.onNodeWithContentDescription("Add").performClick()
        compose.waitUntil(5_000) { phrases() == listOf("סבתא" to true, "Shira" to true) }
        compose.onNode(hasText("Shira") and hasText(otherLetters)).assertExists()

        compose.onNode(hasText("Grandma") and isToggleable()).performClick()
        compose.waitUntil(5_000) { phrases() == listOf("סבתא" to false, "Shira" to true) }
        field.performTextInput("סבתא")
        compose.onNodeWithText("\u201CGrandma\u201D is already in the list, turned off. Tap the plus to turn it back on.").assertExists()
        compose.onNodeWithContentDescription("Turn on").performClick()
        compose.waitUntil(5_000) { phrases() == listOf("סבתא" to true, "Shira" to true) }

        compose.onNodeWithText("Shira").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete \u201CShira\u201D?").assertExists()
        compose.onNodeWithText("The phone will stop alerting when this word is said.").assertExists()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(2, phrases().size)
        val row = compose.onNode(hasText("Shira") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode()
        val delete = row.config[SemanticsActions.CustomActions].single { it.label == "Delete" }
        compose.runOnUiThread { delete.action() }
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { phrases() == listOf("סבתא" to true) }

        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Important words").performScrollTo()
        compose.onNode(hasText("Important words") and hasText("1")).assertExists()
        compose.onNodeWithText("Important words").performClick()
        compose.onNode(hasText("Grandma") and isToggleable()).performClick()
        compose.waitUntil(5_000) { phrases() == listOf("סבתא" to false) }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Important words").performScrollTo()
        compose.onNode(hasText("Important words") and hasText("0")).assertExists()
    }

    @Test
    fun alertsWhileTheScreenIsOffAreSwitchedInSettings() {
        app.settings.change { it.notifyWhenInBackground = true }
        compose.onNodeWithText("Settings").performClick()
        val row = compose.onNode(hasText("Alert on phone when the screen is off") and isToggleable())
        row.performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { !app.settings.current.value.notifyWhenInBackground }
        row.assertIsOff().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.notifyWhenInBackground }
    }

    @Test
    fun theBuzzWhenSomeoneStartsTalkingAfterSilenceIsSwitchedWithTheAlerts() {
        val before = app.settings.current.value.hapticOnSpeechResume
        try {
            app.settings.change { it.hapticOnSpeechResume = true }
            compose.onNodeWithText("Settings").performClick()
            val row = compose.onNode(hasText("Vibrate when someone starts talking after silence") and isToggleable())
            row.performScrollTo().assertIsOn().performClick()
            compose.waitUntil(5_000) { !app.settings.current.value.hapticOnSpeechResume }
            row.assertIsOff().performClick()
            compose.waitUntil(5_000) { app.settings.current.value.hapticOnSpeechResume }
        } finally {
            app.settings.change { it.hapticOnSpeechResume = before }
        }
    }

    @Test
    fun quietHoursAreSetOnlyWithAlertsWhileTheScreenIsOffAndEqualHoursAreCalledTheWholeDay() {
        app.settings.change {
            it.notifyWhenInBackground = true
            it.quietHours = QuietHours(isEnabled = false, startHour = 22, endHour = 7)
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Starts").assertDoesNotExist()
        compose.onNode(hasText("Quiet hours") and isToggleable()).performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.quietHours.isEnabled }
        hour("Starts", "22:00").performScrollTo()
        hour("Ends", "07:00").assertExists()
        compose.onNodeWithText("Urgent sounds like a siren still alert.").assertExists()

        val laterStart = compose.onNode(hasText("+") and hasAnyAncestor(hasText("Starts")))
        laterStart.performScrollTo().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.quietHours.startHour == 23 }
        hour("Starts", "23:00").assertExists()
        laterStart.assertIsNotEnabled()
        compose.onNode(hasText("−") and hasAnyAncestor(hasText("Ends"))).performScrollTo().performClick()
        compose.waitUntil(5_000) { app.settings.current.value.quietHours.endHour == 6 }
        assertEquals(23, app.settings.current.value.quietHours.startHour)

        hour("Ends", "06:00").performSemanticsAction(SemanticsActions.SetProgress) { it(23f) }
        compose.waitUntil(5_000) { app.settings.current.value.quietHours.endHour == 23 }
        compose.onNodeWithText("Starting and ending at the same hour, quiet hours last all day. Urgent sounds like a siren still alert.").performScrollTo()
        assertEquals(QuietHours(isEnabled = true, startHour = 23, endHour = 23), app.settings.current.value.quietHours)

        compose.onNode(hasText("Alert on phone when the screen is off") and isToggleable()).performScrollTo().performClick()
        compose.waitUntil(5_000) { !app.settings.current.value.notifyWhenInBackground }
        compose.onNodeWithText("Quiet hours").assertDoesNotExist()
    }

    @Test
    fun importantWordsHeardLatelyAreListedNewestFirstWithTheirTimes() {
        val grandma = KeywordAlert(phrase = "סבתא")
        app.settings.change { it.keywordAlerts = listOf(grandma) }
        val at = 1_800_000_000.0
        val hits = listOf(
            KeywordHit(segmentID = UUID.randomUUID(), match = KeywordMatch(grandma.id, "סבתא", "סבתא", 0), timestamp = at),
            KeywordHit(segmentID = UUID.randomUUID(), match = KeywordMatch(UUID.randomUUID(), "Shira", "Shira", 0), timestamp = at + 60),
        )
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Important words").performScrollTo().performClick()
        compose.onNodeWithText("Recently heard").assertDoesNotExist()
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList(), recentlyHeard = hits)
        }
        compose.onNodeWithText("Recently heard").performScrollTo()
        val older = compose.onNode(hasText("Grandma") and hasText(clockTime(at))).performScrollTo()
        val newer = compose.onNode(hasText("Shira") and hasText(clockTime(at + 60))).performScrollTo()
        assertTrue(newer.getUnclippedBoundsInRoot().top < older.getUnclippedBoundsInRoot().top)
    }

    @Test
    fun aTestNotificationAskedForInSettingsArrivesTenSecondsLater() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val tests = { manager.activeNotifications.filter { it.tag == "test-alert" } }
        manager.cancel("test-alert", 0)
        app.settings.change { it.notifyWhenInBackground = true }
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText(BLOCKED).assertDoesNotExist()
            compose.onNodeWithText("Test that an alert arrives when the phone is locked").performScrollTo().performClick()
            compose.onNodeWithText("Sent. Lock the phone, and it will arrive within 10 seconds").assertExists()
            Thread.sleep(8_000)
            assertTrue(tests().isEmpty())
            val until = System.currentTimeMillis() + 6_000
            while (tests().isEmpty() && System.currentTimeMillis() < until) Thread.sleep(100)
            val posted = tests().single().notification
            assertEquals("Test: doorbell", posted.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(AlertNotifier.CHANNEL_ID, posted.channelId)

            compose.onNode(hasText("Alert on phone when the screen is off") and isToggleable()).performScrollTo().performClick()
            compose.waitUntil(5_000) { !app.settings.current.value.notifyWhenInBackground }
            compose.onNodeWithText("Sent. Lock the phone, and it will arrive within 10 seconds").assertDoesNotExist()
        } finally {
            manager.cancel("test-alert", 0)
        }
    }

    @Test
    fun notificationsTurnedOffInThePhoneAreSaidLeadToItsSettingsAndAreCheckedAgainOnReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        }
        val focused = { shell("dumpsys window").lineSequence().firstOrNull { "mCurrentFocus" in it }.orEmpty() }
        val allowedBefore = AlertNotifier.allowed
        app.settings.change { it.notifyWhenInBackground = true }
        AlertNotifier.allowed = { false }
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText(BLOCKED).performScrollTo()
            compose.onNodeWithText("Test that an alert arrives when the phone is locked").assertDoesNotExist()
            compose.onNodeWithText("Open the phone’s settings").performScrollTo().performClick()
            val until = System.currentTimeMillis() + 10_000
            while ("AppNotificationSettingsActivity" !in focused() && System.currentTimeMillis() < until) Thread.sleep(200)
            assertTrue(focused(), "AppNotificationSettingsActivity" in focused())

            AlertNotifier.allowed = { true }
            shell("input keyevent KEYCODE_BACK")
            val back = System.currentTimeMillis() + 10_000
            while (app.packageName !in focused() && System.currentTimeMillis() < back) Thread.sleep(200)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(BLOCKED).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Test that an alert arrives when the phone is locked").performScrollTo()
        } finally {
            AlertNotifier.allowed = allowedBefore
        }
    }

    @Test
    fun aFullWordListSaysSoAndTakesNoMore() {
        val full = (1..200).map { "Name number $it" }
        app.settings.change { it.vocabulary = full }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Names and special words").performScrollTo().performClick()
        compose.onNodeWithText("200 / 200").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("One more")
        compose.onNodeWithText("The list is full. Delete a word to add another.").assertExists()
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertEquals(full, app.settings.current.value.vocabulary)
    }

    @Test
    fun theSpeechRateIsKeptAndTheSampleIsSaidThatFast() {
        assertTrue("no voice on the phone", within(15_000) { app.captions.canAskToRepeat() })
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Say something (type to speak)").performScrollTo()
        compose.onNodeWithText("Captions pause while the phone is speaking, and resume on their own.").performScrollTo()
        val rate = compose.onNodeWithContentDescription("Speech rate")
        val range = rate.performScrollTo().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.2f..0.7f, range.range)
        assertEquals(9, range.steps)

        rate.performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        compose.waitUntil(5_000) { abs(app.settings.current.value.speechRate - 0.7f) < 0.001f }
        rate.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0.70"))
        val fast = sampleMillis()
        rate.performSemanticsAction(SemanticsActions.SetProgress) { it(0.2f) }
        compose.waitUntil(5_000) { abs(app.settings.current.value.speechRate - 0.2f) < 0.001f }
        rate.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0.20"))
        val slow = sampleMillis()
        assertTrue("the sample took $slow ms slow and $fast ms fast", slow > fast * 1.8)
    }

    @Test
    fun diagnosticsShowWhatTheCaptionsAreDoingNewestEventFirstAndTheReportGoesOutWithoutThePairingCode() {
        val started = CopyOnWriteArrayList<Intent>()
        val sheet = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                started += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        app.homeServerCode.save("code-for-the-report")
        app.settings.change { it.homeServerAddress = "192.168.1.20:8765" }
        compose.runOnUiThread {
            app.captions.pipeline.systemInterruptionChanged(true)
            app.captions.pipeline.systemInterruptionChanged(false)
        }
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        compose.runOnUiThread { clipboard.clearPrimaryClip() }
        instrumentation.addMonitor(sheet)
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
            compose.onNodeWithText("Stage").assertExists()
            compose.onNodeWithText(DiagnosticsReport.stage(app.captions.pipeline.phase)).assertExists()
            val events = hasAnyAncestor(hasTestTag(DIAGNOSTICS_EVENTS))
            val givenBack = compose.onAllNodes(hasText("audio given back", substring = true) and events).fetchSemanticsNodes().minOf { it.positionInRoot.y }
            val taken = compose.onAllNodes(hasText("audio taken by a call or another app", substring = true) and events).fetchSemanticsNodes().minOf { it.positionInRoot.y }
            assertTrue("the newest event is not at the top", givenBack < taken)
            val calls = { compose.onAllNodes(hasText("audio taken by a call or another app", substring = true) and events).fetchSemanticsNodes().size }
            val before = calls()
            compose.runOnUiThread { app.captions.pipeline.systemInterruptionChanged(true) }
            compose.waitUntil(5_000) { calls() == before + 1 }
            compose.runOnUiThread { app.captions.pipeline.systemInterruptionChanged(false) }

            compose.onNodeWithText("Copy report").performScrollTo().performClick()
            compose.onNodeWithText("Copied").assertExists()
            var copied = ""
            compose.runOnUiThread { copied = clipboard.primaryClip?.getItemAt(0)?.text.toString() }
            assertTrue(copied, copied.startsWith("Ozen diagnostics\nphase: "))
            assertTrue(copied, copied.contains("home server: 192.168.1.20:8765 code saved: true"))
            val eventsInReport = copied.substringBefore("journal, previous runs included")
            assertTrue("events not oldest first", eventsInReport.lastIndexOf("audio taken by a call or another app") < eventsInReport.lastIndexOf("audio given back"))
            assertFalse("the pairing code went into the report", copied.contains("code-for-the-report"))

            compose.onNodeWithText("Send report").performScrollTo().performClick()
            compose.waitUntil(5_000) { started.isNotEmpty() }
            assertEquals(Intent.ACTION_CHOOSER, started.single().action)
            @Suppress("DEPRECATION")
            val send = started.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("Ozen diagnostics report", send.getStringExtra(Intent.EXTRA_SUBJECT))
            val sent = send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            assertTrue(sent, sent.startsWith("Ozen diagnostics\nphase: ") && sent.contains("audio given back"))
            assertFalse("the pairing code went into the report", sent.contains("code-for-the-report"))

            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("Diagnostics").assertExists()
        } finally {
            instrumentation.removeMonitor(sheet)
        }
    }

    @Test
    fun theJournalKeepsWhatHappenedOnDiskAndAMarkedProblemGoesIntoTheReportWithItsCaptionLinesDeletableApart() {
        val kept = SessionJournal(File(app.filesDir, CaptionSession.JOURNAL_FILE))
        val clock = { System.currentTimeMillis() / 1_000.0 }
        val since = { from: Double -> kept.entries().filter { it.at >= from - 0.01 }.map { it.text } }
        val processStarted = clock() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1_000.0
        kept.removeEntries(ProblemSnapshot::isCaptionLine)
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        compose.runOnUiThread { clipboard.clearPrimaryClip() }
        assertTrue(since(processStarted - 1).any { it.startsWith("APP STARTED: ") && it.contains(" Android ") && it.contains(" engine ") })

        val away = clock()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertTrue(within(5_000) { since(away).filter { it.startsWith("app ") } == listOf("app left the screen (phase not listening)", "app back on screen") })
        val call = clock()
        compose.runOnUiThread {
            app.captions.pipeline.systemInterruptionChanged(true)
            app.captions.pipeline.systemInterruptionChanged(false)
        }
        assertTrue(within(5_000) { since(call).let { it.indexOf("audio taken by a call or another app") in 0 until it.indexOf("audio given back") } })
        val backOnScreen = compose.runOnUiThread { app.captions.diagnostics().journal.last { it.endsWith(" app back on screen") } }

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
        val journal = hasAnyAncestor(hasTestTag(DIAGNOSTICS_JOURNAL))
        compose.onNodeWithText("Journal, previous runs included").performScrollTo()
        compose.onNodeWithText("Delete the caption lines kept here").assertDoesNotExist()
        val marked = clock()
        compose.onNodeWithText("Mark a problem now").performScrollTo().performClick()
        assertTrue(since(marked).any { it.startsWith("PROBLEM MARKED: engine ") })
        val problem = compose.runOnUiThread { app.captions.diagnostics().journal.last { it.contains(" PROBLEM MARKED: engine ") } }
        val problemAt = compose.onAllNodes(hasText(problem) and journal).fetchSemanticsNodes().minOf { it.positionInRoot.y }
        val backAt = compose.onAllNodes(hasText(backOnScreen) and journal).fetchSemanticsNodes().minOf { it.positionInRoot.y }
        assertTrue("the journal is not newest first", problemAt < backAt)

        val said = "  line (final, sure 0.91, 12:00:00): The pharmacy closes early on Friday"
        kept.append(said, clock())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete the caption lines kept here").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Delete the caption lines kept here").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete the caption lines kept here").fetchSemanticsNodes().isEmpty() }
        assertFalse(kept.entries().any { it.text == said })
        assertTrue("deleting the caption lines took the rest with them", since(marked).any { it.startsWith("PROBLEM MARKED: engine ") })

        compose.onNodeWithText("Copy report").performScrollTo().performClick()
        val copied = compose.runOnUiThread { clipboard.primaryClip?.getItemAt(0)?.text.toString() }
        val inReport = copied.substringAfter("journal, previous runs included (oldest first):\n", missingDelimiterValue = "")
        assertTrue(copied, inReport.indexOf(backOnScreen) in 0 until inReport.indexOf(problem))
    }

    @Test
    fun soundKeptWithMarkedProblemsIsListedNewestFirstSentOnTapAndDeletedByHoldingOrTheScreenReader() {
        val store = ProblemAudioStore(File(app.filesDir, CaptionSession.PROBLEM_AUDIO_FOLDER))
        store.deleteAll()
        val older = store.save(floatArrayOf(0.1f, 0.2f), 16_000, Instant.parse("2026-03-04T05:06:07Z"))!!
        val newer = store.save(floatArrayOf(0.3f), 16_000, Instant.parse("2026-03-05T05:06:07Z"))!!
        val olderTitle = DiagnosticsReport.clipTitle(older.name)
        val newerTitle = DiagnosticsReport.clipTitle(newer.name)
        val started = CopyOnWriteArrayList<Intent>()
        val sheet = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                started += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(sheet)
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
            compose.onNodeWithText("Sound from marked problems").performScrollTo()
            val newerAt = compose.onNodeWithText(newerTitle).fetchSemanticsNode().positionInRoot.y
            val olderAt = compose.onNodeWithText(olderTitle).fetchSemanticsNode().positionInRoot.y
            assertTrue("the newest sound is not at the top", newerAt < olderAt)

            compose.onNodeWithText(newerTitle).performScrollTo().performClick()
            compose.waitUntil(5_000) { started.isNotEmpty() }
            assertEquals(Intent.ACTION_CHOOSER, started.single().action)
            @Suppress("DEPRECATION")
            val send = started.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("audio/wav", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION")
            val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertArrayEquals(newer.readBytes(), app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })

            compose.onNodeWithText(olderTitle).performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(olderTitle).fetchSemanticsNodes().isEmpty() }
            assertFalse(older.exists())
            assertTrue(newer.exists())

            val delete = compose.onNodeWithText(newerTitle).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Delete" }
            compose.runOnUiThread { delete.action() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Sound from marked problems").fetchSemanticsNodes().isEmpty() }
            assertTrue(store.clips().isEmpty())
        } finally {
            instrumentation.removeMonitor(sheet)
            store.deleteAll()
        }
    }

    @Test
    fun diagnosticsSayWhetherAlertsCanReachHerWhatConnectionThereIsAndWhatTheEngineIsToldToExpect() {
        val allowedBefore = AlertNotifier.allowed
        AlertNotifier.allowed = { false }
        app.settings.change {
            it.vocabulary = listOf("Shira", "Ozen", "Netanya")
            it.keywordAlerts = listOf(KeywordAlert(phrase = "Grandma"), KeywordAlert(phrase = "Doctor", isEnabled = false))
        }
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        compose.runOnUiThread { clipboard.clearPrimaryClip() }
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
            row("Phone notifications", "Turned off in Android settings")
            row("Alert vibration", if (phoneVibrator(app)?.hasVibrator() == true) "OK" else "Not supported on this device")
            val connection = DiagnosticsReport.network(app.captions.pipeline.networkConditions)
            assertNotEquals("—", connection)
            row("Internet connection", connection)
            row("Model state", if (File(app.filesDir, CaptionSession.MODEL_FILE).exists()) "Installed (unverified)" else "Not downloaded")
            row("Names and words", "3")
            row("Word alerts", "1")

            AlertNotifier.allowed = { true }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Phone notifications") and hasText("OK")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Copy report").performScrollTo().performClick()
            val copied = compose.runOnUiThread { clipboard.primaryClip?.getItemAt(0)?.text.toString() }
            assertTrue(copied, copied.contains(" notifications allowed: yes haptics: ") && copied.contains(" network: $connection\n"))
            assertTrue(copied, copied.contains("\nmodel state: ") && copied.contains(" vocabulary: 3\n"))
        } finally {
            AlertNotifier.allowed = allowedBefore
        }
    }

    @Test
    fun theAppLanguageIsChosenByEachLanguagesOwnNameAndTheScreensFollowItAtOnce() {
        val before = app.settings.current.value.appLanguage
        val spokenBefore = app.settings.current.value.languageCode
        try {
            app.settings.change { it.languageCode = "en" }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Same as the phone").performScrollTo().performClick()
            compose.onNodeWithText("العربية").assertExists()
            compose.onNodeWithText("עברית").performClick()
            compose.waitUntil(5_000) { app.settings.current.value.appLanguage == AppLanguage.Hebrew }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("הגדרות").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("שפת האפליקציה").assertExists()

            compose.onNodeWithText("עברית").performScrollTo().performClick()
            compose.onNodeWithText("כמו בטלפון").performClick()
            compose.waitUntil(5_000) { app.settings.current.value.appLanguage == AppLanguage.System }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("App language").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            app.settings.change {
                it.appLanguage = before
                it.languageCode = spokenBefore
            }
        }
    }

    @Test
    fun theSpokenLanguageIsChosenByItsSpokenNameAndIsWhatTheEnginesAreGiven() {
        val before = app.settings.current.value.languageCode
        try {
            app.settings.change { it.languageCode = "he" }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Spoken language").performScrollTo().assertExists()
            compose.onNodeWithText("The language people around you speak. The captions are written in it.").performScrollTo().assertExists()
            compose.onNodeWithText(UILanguage.Hebrew.spokenName).performScrollTo().performClick()
            compose.onNodeWithText("中文").assertExists()
            compose.onNodeWithText("English").performClick()
            compose.waitUntil(5_000) { app.settings.current.value.languageCode == "en" }
            compose.onNodeWithText("English").performScrollTo().assertExists()
            compose.onNodeWithText("The language of the buttons and settings, not of the captions.").performScrollTo().assertExists()
        } finally {
            app.settings.change { it.languageCode = before }
        }
    }

    @Test
    fun diagnosticsReportTheModelForTheSpokenLanguageSoOzensHebrewModelDoesNotCountForEnglish() {
        val hebrewModel = File(app.filesDir, CaptionSession.MODEL_FILE)
        val englishModel = File(app.filesDir, WhisperModelCatalog.phoneModelFile(UILanguage.English))
        assumeTrue("needs Ozen's Hebrew model on the phone and not the general one", hebrewModel.exists() && !englishModel.exists())
        val before = app.settings.current.value.languageCode
        try {
            app.settings.change { it.languageCode = "en" }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
            row("Model state", "Not downloaded")
        } finally {
            app.settings.change { it.languageCode = before }
        }
    }

    @Test
    fun settingsShowsTheWalkthroughAgainOnceSheConfirmsWithCaptionsStoppedUntilItEnds() {
        try {
            compose.runOnUiThread { app.captions.listen() }
            compose.waitUntil(10_000) { app.captions.pipeline.phase != PipelinePhase.Idle }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Show the initial walkthrough again").performScrollTo().performClick()
            compose.onNodeWithText("Captions stop until the walkthrough ends.").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            assertTrue(app.settings.current.value.hasCompletedOnboarding)
            assertNotEquals(PipelinePhase.Idle, app.captions.pipeline.phase)

            compose.onNodeWithText("Show the initial walkthrough again").performScrollTo().performClick()
            compose.onNodeWithText("Show it").performClick()
            compose.waitUntil(5_000) { !app.settings.current.value.hasCompletedOnboarding }
            compose.waitUntil(10_000) { app.captions.pipeline.phase == PipelinePhase.Idle }
            compose.onNodeWithText("Live captions for conversation.").assertExists()
            compose.onNodeWithText("Skip").performClick()
            compose.waitUntil(5_000) { app.settings.current.value.hasCompletedOnboarding }
            compose.onNodeWithText("History").assertExists()
            compose.waitUntil(10_000) { app.captions.pipeline.phase != PipelinePhase.Idle }
        } finally {
            compose.runOnUiThread {
                if (app.captions.pipeline.phase != PipelinePhase.Idle) app.captions.pipeline.stop()
                app.captions.launchHandled = true
            }
            app.settings.change { it.hasCompletedOnboarding = true }
        }
    }

    @Test
    fun clearingTheCaptionsFromSettingsAsksFirstThenEmptiesTheScreen() {
        val line = TranscriptSegment(id = UUID.randomUUID(), text = "Dinner is ready", isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(line))
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Clear captions from the screen").performScrollTo().performClick()
        compose.onNodeWithText("Delete all captions from the screen?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, CaptionState.screen.value.lines.size)
        compose.onNodeWithText("Clear captions from the screen").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { CaptionState.screen.value.lines.isEmpty() }
    }

    @Test
    fun settingsAndDiagnosticsStayOpenWhenThePhoneRebuildsTheScreen() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Diagnostics").performScrollTo().assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Recent events").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Recent events").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Diagnostics").performScrollTo().assertExists()
    }

    @Test
    fun diagnosticsShowThePhonesHeatBatteryAndBatterySaverAsTheyAre() {
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        val shown = { label: String, value: String -> compose.onAllNodes(hasText(label) and hasText(value)).fetchSemanticsNodes().isNotEmpty() }
        try {
            shell("dumpsys battery unplug")
            shell("dumpsys battery set level 37")
            shell("cmd thermalservice override-status 3")
            shell("cmd power set-mode 1")
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
            compose.waitUntil(10_000) { shown("Battery", "37%") && shown("Battery Saver", "On") }
            row("Temperature", "Hot · captions are slowing down")
            row("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
            row("Android", Build.VERSION.RELEASE)
            compose.onNode(hasText("Free space") and textMatching(Regex("^[0-9.]+\u00A0(KB|MB|GB)$"))).performScrollTo().assertExists()
            compose.onNode(hasText("Memory") and textMatching(Regex("^[0-9.]+\u00A0(KB|MB|GB) in use · [0-9.]+\u00A0(KB|MB|GB) more$"))).performScrollTo().assertExists()

            shell("dumpsys battery set ac 1")
            shell("cmd power set-mode 0")
            shell("cmd thermalservice override-status 0")
            compose.waitUntil(10_000) { shown("Battery", "37% · charging") && shown("Battery Saver", "Off") && shown("Temperature", "Normal") }
        } finally {
            shell("cmd power set-mode 0")
            shell("cmd thermalservice reset")
            shell("dumpsys battery reset")
        }
    }

    private fun textMatching(pattern: Regex) = SemanticsMatcher("text matching $pattern") { node ->
        runCatching { node.config[SemanticsProperties.Text] }.getOrNull()?.any { pattern.matches(it.text) } == true
    }

    private fun row(label: String, value: String) {
        compose.onNode(hasText(label) and hasText(value)).performScrollTo().assertExists()
    }

    private fun sampleMillis(): Long {
        compose.onNodeWithText("Play a sample").performScrollTo().performClick()
        assertTrue("never spoke", within(10_000) { app.captions.isSpeaking })
        val start = System.currentTimeMillis()
        assertTrue("never finished", within(30_000) { !app.captions.isSpeaking })
        return System.currentTimeMillis() - start
    }

    private fun within(millis: Long, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun hour(title: String, shown: String) =
        compose.onNode(hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shown))

    private fun assertReadableOnWhite(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = true).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val colour = layouts.single().layoutInput.style.color
        val ratio = ContrastRatio.ratio(Triple(colour.red.toDouble(), colour.green.toDouble(), colour.blue.toDouble()), colour.alpha.toDouble(), Triple(1.0, 1.0, 1.0))
        assertTrue("$text: $ratio to 1", ratio >= 4.5)
    }

    private companion object {
        const val BLOCKED = "Ozen’s notifications are turned off in the phone’s settings, so no alert will arrive when the screen is off."
    }
}
