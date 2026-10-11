package com.arbelonson.ozen

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.CloudSpeech
import com.arbelonson.ozen.core.ContrastRatio
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.QuietHours
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun hour(title: String, shown: String) =
        compose.onNode(hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, shown))

    private fun assertReadableOnWhite(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = true).performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val colour = layouts.single().layoutInput.style.color
        val ratio = ContrastRatio.ratio(Triple(colour.red.toDouble(), colour.green.toDouble(), colour.blue.toDouble()), colour.alpha.toDouble(), Triple(1.0, 1.0, 1.0))
        assertTrue("$text: $ratio to 1", ratio >= 4.5)
    }
}
