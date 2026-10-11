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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.KeywordMatch
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.SessionJournal
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptionScreenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun clear() {
        compose.runOnUiThread { CaptionState.clear() }
    }

    @Test
    fun aLineThatOpensInEnglishButIsHebrewIsDrawnRightToLeft() {
        val line = TranscriptSegment(
            id = UUID.randomUUID(),
            text = "OK, \u05D0\u05D6 \u05E0\u05EA\u05D7\u05D9\u05DC",
            isCommitted = true,
            startTimestamp = 0.0,
            lastUpdateTimestamp = 0.0,
        )
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(line))
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("\u05E0\u05EA\u05D7\u05D9\u05DC", substring = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(ResolvedTextDirection.Rtl, layouts.single().getParagraphDirection(0))
    }

    @Test
    fun aTimeInAFinishedLineIsDrawnHeavierInYellowUnlessBoldNumbersAreOff() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.display
        try {
            app.settings.change { it.display = it.display.copy(emphasizeNumbers = true) }
            showLine("Meet at 10:30 by the door")
            val numbers = layoutOf("Meet at").layoutInput.text.spanStyles
            assertEquals(1, numbers.size)
            assertEquals(FontWeight.ExtraBold, numbers.single().item.fontWeight)
            assertEquals(NUMBER_YELLOW, numbers.single().item.color)

            app.settings.change { it.display = it.display.copy(emphasizeNumbers = false) }
            compose.waitForIdle()
            assertTrue(layoutOf("Meet at").layoutInput.text.spanStyles.isEmpty())
        } finally {
            app.settings.change { it.display = before }
        }
    }

    @Test
    fun blackOnWhiteDrawsDarkWordsUnderDarkBarIconsAndWhiteOnBlackTheOpposite() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.display
        try {
            app.settings.change { it.display = it.display.copy(theme = DisplayPreferences.Theme.Light, emphasizeNumbers = true) }
            showLine("Meet at 10:30 by the door")
            compose.waitForIdle()
            val light = layoutOf("Meet at")
            assertEquals(Color.Black, light.layoutInput.style.color)
            assertEquals(Color(red = 0f, green = 0.25f, blue = 0.7f), light.layoutInput.text.spanStyles.single().item.color)
            compose.waitUntil(5_000) { darkBarIcons() }

            app.settings.change { it.display = it.display.copy(theme = DisplayPreferences.Theme.Dark) }
            compose.waitForIdle()
            assertEquals(Color.White, layoutOf("Meet at").layoutInput.style.color)
            compose.waitUntil(5_000) { !darkBarIcons() }
            assertFalse(darkBarIcons())
        } finally {
            app.settings.change { it.display = before }
        }
    }

    @Test
    fun theButtonsSlideAwayWhileCaptionsRunUntouchedAndComeBackWithATap() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.display
        try {
            app.settings.change { it.display = it.display.copy(autoHideControls = true) }
            showLine("Captions running on their own", PipelinePhase.Listening)
            compose.onNodeWithText("Settings").assertExists()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Show the buttons").performClick()
            compose.onNodeWithText("Settings").assertExists()

            app.settings.change { it.display = it.display.copy(autoHideControls = false) }
            val later = System.currentTimeMillis() + 7_000
            compose.waitUntil(10_000) { System.currentTimeMillis() >= later }
            compose.onNodeWithText("Settings").assertExists()
        } finally {
            app.settings.change { it.display = before }
        }
    }

    @Test
    fun anUnsureLineHasAQuestionMarkAtItsStartAndDotsUnderTheDoubtedWord() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.display
        val doubted = "\u05D4\u05E8\u05D5\u05E4\u05D0"
        val hebrew = "\u05D0\u05E6\u05DC $doubted"
        try {
            app.settings.change { it.display = it.display.copy(markUncertainLines = true) }
            showUnsure(hebrew, doubtful = listOf(doubted))
            val mark = compose.onNodeWithContentDescription("May not have been heard correctly.").fetchSemanticsNode().boundsInRoot
            val words = compose.onNodeWithText(doubted, substring = true)
            val line = words.fetchSemanticsNode().boundsInRoot
            assertTrue("mark $mark, line $line", mark.left >= line.right - 1f)
            assertEquals(1, layoutOf(doubted).layoutInput.text.let { it.getStringAnnotations(DOUBTFUL_WORD, 0, it.length) }.size)
            words.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Words that may be wrong: $doubted"))
            val dotted = words.captureToImage()
            showUnsure(hebrew, doubtful = emptyList())
            val plain = compose.onNodeWithText(doubted, substring = true).captureToImage()
            assertEquals(dotted.width to dotted.height, plain.width to plain.height)
            assertFalse("the dots changed nothing", pixels(dotted).contentEquals(pixels(plain)))

            showUnsure("see you at ten", doubtful = emptyList())
            val englishMark = compose.onNodeWithContentDescription("May not have been heard correctly.").fetchSemanticsNode().boundsInRoot
            val english = compose.onNodeWithText("see you at ten", substring = true).fetchSemanticsNode().boundsInRoot
            assertTrue("mark $englishMark, line $english", englishMark.right <= english.left + 1f)

            app.settings.change { it.display = it.display.copy(markUncertainLines = false) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("May not have been heard correctly.").assertDoesNotExist()
        } finally {
            app.settings.change { it.display = before }
        }
    }

    @Test
    fun scrollingBackToRereadKeepsThePlaceAndAButtonLeadsBackToTheNewestLine() {
        val lines = (1..30).map { committed("Line $it here") }
        showLines(lines)
        assertTrue(displayed("Line 30 here"))
        repeat(3) { compose.onNode(hasScrollAction()).performTouchInput { swipeDown() } }
        compose.waitForIdle()
        assertFalse(displayed("Line 30 here"))

        showLines(lines + (31..32).map { committed("Line $it here") })
        assertFalse(displayed("Line 32 here"))
        compose.onNodeWithText("2 new lines").performClick()
        compose.waitForIdle()
        assertTrue(displayed("Line 32 here"))
        compose.onNodeWithText("2 new lines").assertDoesNotExist()
    }

    @Test
    fun aLineTallerThanTheScreenIsFollowedToItsNewestWords() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.display
        try {
            app.settings.change { it.display = it.display.copy(fontSize = DisplayPreferences.maximumFontSize) }
            showLines(listOf(committed((1..150).joinToString(" ") { "word$it" } + " lastword")))
            val line = compose.onNodeWithText("lastword", substring = true).fetchSemanticsNode()
            val screen = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
            val lineTop = line.positionInRoot.y
            val lineBottom = lineTop + line.size.height
            assertTrue("line $lineTop..$lineBottom, screen $screen", line.size.height > screen.height)
            assertEquals(screen.bottom, lineBottom, 2f)
        } finally {
            app.settings.change { it.display = before }
        }
    }

    @Test
    fun aCaptionLineIsMarkedAsImportantOrCopiedFromItsHoldMenuAndTheScreenReader() {
        val pills = committed("Take two pills after dinner")
        val phone = committed("תתקשרי למרפאה 050 123 4567")
        showLines(listOf(pills, phone))
        compose.onNodeWithText("Take two pills after dinner", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Mark as important").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Marked as important.").fetchSemanticsNodes().size == 1 }
        assertTrue(CaptionState.screen.value.lines.single { it.id == pills.id }.starred)
        assertFalse(CaptionState.screen.value.lines.single { it.id == phone.id }.starred)

        val line = compose.onNodeWithText("Take two pills after dinner", substring = true).fetchSemanticsNode()
        val unmark = line.config[SemanticsActions.CustomActions].single { it.label == "Remove mark" }
        compose.runOnUiThread { unmark.action() }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Marked as important.").fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithText("תתקשרי", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Copy").performClick()
        compose.waitUntil(5_000) { copied() == CaptionLayout.copiedText("תתקשרי למרפאה 050 123 4567") }
    }

    @Test
    fun captionsNotGoodMarksAProblemInTheJournalFromTheHoldMenuOrTheScreenReaderAndSaysWhereItWent() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val kept = SessionJournal(File(app.filesDir, CaptionSession.JOURNAL_FILE))
        val held = System.currentTimeMillis() / 1_000.0
        val marks = { kept.entries().count { it.at >= held - 0.01 && it.text.startsWith("PROBLEM MARKED: engine ") } }
        val problem = "Captions not good? Mark a problem"
        showLines(listOf(committed("The bus leaves at noon")))
        lineText("The bus leaves at noon").performTouchInput { longClick() }
        compose.onNodeWithText(problem).performClick()
        compose.onNodeWithText("Problem marked").assertExists()
        compose.onNodeWithText("The problem's details and the last few lines were saved, on the phone only. To send them to whoever helps you: Settings → Diagnostics → Send report.").assertExists()
        assertEquals(1, marks())
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Problem marked").assertDoesNotExist()

        val mark = lineText("The bus leaves at noon").fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == problem }
        compose.runOnUiThread { mark.action() }
        compose.onNodeWithText("Problem marked").assertExists()
        assertEquals(2, marks())
        compose.onNodeWithText("OK").performClick()
    }

    @Test
    fun theMicrophoneButtonOpensThePickerWhereChoosingOneSwitchesToItOrSaysThePhoneWouldNot() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val preferredBefore = app.settings.current.value.preferredInputUID
        try {
            compose.onNodeWithContentDescription("Choose microphone").performClick()
            compose.onNodeWithText("Available microphones").assertExists()
            compose.onNodeWithText("The meter is only active while listening.").performScrollTo().assertExists()
            val real = app.captions.pipeline.availableInputs
            assertTrue("the picker found no microphone on the phone", real.isNotEmpty())
            val phone = real.first()
            compose.onNode(hasText(phone.portName) and isSelectable()).performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(phone.portName) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(phone.uid, app.settings.current.value.preferredInputUID)

            val lapel = AudioInputDescriptor("usb:lapel", "Lapel mic", AudioPortType.Usb)
            compose.runOnUiThread { CaptionState.inputs(real + lapel, phone.uid) }
            compose.onNode(hasText("Lapel mic") and isSelectable()).performScrollTo().performClick()
            compose.onNodeWithText("The phone did not switch to “Lapel mic”, and the marked microphone is still recording. Try unplugging and reconnecting it.").performScrollTo().assertExists()
            compose.onNode(hasText(phone.portName) and isSelected()).assertExists()

            compose.runOnUiThread { CaptionState.inputs(emptyList(), null) }
            compose.onNodeWithText("No microphones available").assertExists()
            compose.onNodeWithText("Refresh").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(phone.portName) and isSelectable()).fetchSemanticsNodes().isNotEmpty() }

            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Available microphones").assertDoesNotExist()
            compose.onNodeWithContentDescription("Choose microphone").assert(hasText(if (phone.portType == AudioPortType.BuiltInMic) "Phone" else phone.portName))
        } finally {
            app.settings.change { it.preferredInputUID = preferredBefore }
        }
    }

    @Test
    fun theOfferToBuzzForHerNameOpensTheNameFormAndGoesOnceANameIsInOrSheSaysNotNow() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.copy()
        val offer = "Have the phone buzz when someone calls your name?"
        try {
            app.settings.change {
                it.keywordAlerts = emptyList()
                it.nameAlertOfferDismissed = false
            }
            compose.onNodeWithText(offer).performClick()
            compose.onNodeWithText("When your name is called").assertExists()
            compose.onNodeWithText("Your name").performTextInput("Dana")
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(5_000) { app.settings.current.value.keywordAlerts.map { it.phrase } == listOf("Dana") }
            assertEquals("Dana", app.settings.current.value.vocabulary.first())
            compose.onNodeWithText(offer).assertDoesNotExist()

            app.settings.change { it.keywordAlerts = emptyList() }
            compose.onNodeWithContentDescription("Not now").performClick()
            compose.waitUntil(5_000) { app.settings.current.value.nameAlertOfferDismissed }
            compose.onNodeWithText(offer).assertDoesNotExist()
        } finally {
            app.settings.change {
                it.keywordAlerts = before.keywordAlerts
                it.vocabulary = before.vocabulary
                it.nameAlertOfferDismissed = before.nameAlertOfferDismissed
            }
        }
    }

    @Test
    fun openingTheAppStartsTheCaptionsOnceAndComingBackToTheCaptionScreenDoesNotStartThemAgain() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val stopped = { compose.runOnUiThread { app.captions.pipeline.stop() } }
        try {
            compose.runOnUiThread { app.captions.launchHandled = false }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Close").performScrollTo().performClick()
            compose.waitUntil(10_000) { app.captions.pipeline.phase != PipelinePhase.Idle }
            stopped()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Close").performScrollTo().performClick()
            compose.activityRule.scenario.recreate()
            val until = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < until) {
                assertEquals("the captions started again", PipelinePhase.Idle, app.captions.pipeline.phase)
                Thread.sleep(100)
            }
        } finally {
            stopped()
            app.captions.launchHandled = true
        }
    }

    @Test
    fun theMicrophoneButtonShrinksItsWordToFitRatherThanCuttingItOff() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        compose.runOnUiThread { CaptionState.inputs(app.captions.pipeline.availableInputs, null) }
        compose.onNodeWithContentDescription("Choose microphone").assert(hasText("Microphone"))
        assertFalse("the word on the microphone button was cut off", layoutOf("Microphone").isLineEllipsized(0))
    }

    @Test
    fun aMicrophoneThatDroppedIsSaidAtTheTopWhereATapOpensThePickerAndCloseOnlyPutsItAway() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val lapel = AudioInputDescriptor("usb:lapel", "Lapel mic", AudioPortType.Usb)
        val phone = AudioInputDescriptor("builtInMic:test", "Phone microphone", AudioPortType.BuiltInMic)
        val unplugged = {
            compose.runOnUiThread {
                app.captions.pipeline.microphoneDrop.inputChanged(lapel, phone, isListening = true)
                app.captions.refreshInputs()
            }
        }
        try {
            unplugged()
            compose.onNodeWithText("Lapel mic disconnected", substring = true).assertIsDisplayed()
            compose.onNodeWithText("When captions start again they will use the phone's own microphone and may be less accurate. Reconnect it to go back to it.", substring = true).assertExists()
            compose.runOnUiThread { CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList()) }
            compose.onNodeWithText("Captions carry on through the phone's own microphone and may be less accurate. Reconnect it to go back to it.", substring = true).assertExists()

            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithText("Lapel mic disconnected", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Available microphones").assertDoesNotExist()

            unplugged()
            compose.onNodeWithText("Lapel mic disconnected", substring = true).performClick()
            compose.onNodeWithText("Available microphones").assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Available microphones").assertDoesNotExist()
            compose.onNodeWithText("Lapel mic disconnected", substring = true).assertDoesNotExist()
        } finally {
            compose.runOnUiThread { app.captions.dismissMicrophoneDrop() }
        }
    }

    @Test
    fun theCaptionsFollowThePhoneTurningDarkOrLightWithoutTheScreenBeingRebuilt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        }
        val resumed = {
            var shown: Activity? = null
            instrumentation.runOnMainSync { shown = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() }
            shown
        }
        val background = { compose.onRoot().captureToImage().toPixelMap().let { it[it.width / 2, it.height / 4] } }
        val nightBefore = shell("cmd uimode night").substringAfter("Night mode: ").trim()
        val displayBefore = app.settings.current.value.display
        try {
            app.settings.change { it.display = it.display.copy(theme = DisplayPreferences.Theme.MatchPhone) }
            val darkFirst = nightBefore != "yes"
            for (dark in listOf(darkFirst, !darkFirst)) {
                val first = resumed()
                shell("cmd uimode night ${if (dark) "yes" else "no"}")
                val until = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < until) {
                    assertTrue("the screen was rebuilt when the phone turned ${if (dark) "dark" else "light"}", resumed() === first)
                    Thread.sleep(50)
                }
                compose.waitUntil(5_000) { background() == if (dark) Color.Black else Color.White }
            }
        } finally {
            shell("cmd uimode night $nightBefore")
            app.settings.change { it.display = displayBefore }
        }
    }

    @Test
    fun aSettledPhoneNumberOnTheCaptionScreenOpensTheDialerAndOneStillBeingHeardDoesNot() {
        val settled = committed("050 123 4567")
        val growing = TranscriptSegment(id = UUID.randomUUID(), text = "052 765 4321", isCommitted = false, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        val started = CopyOnWriteArrayList<Intent>()
        val dialer = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                started += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(dialer)
        try {
            showLines(listOf(settled, growing))
            lineText("052 765 4321").performTouchInput { click(Offset(40f, centerY)) }
            compose.waitForIdle()
            assertTrue(started.isEmpty())
            assertTrue(lineText("052 765 4321").fetchSemanticsNode().config[SemanticsActions.CustomActions].none { it.label.startsWith("Call") })

            lineText("050 123 4567").performTouchInput { click(Offset(40f, centerY)) }
            compose.waitUntil(5_000) { started.isNotEmpty() }
            assertEquals(Intent.ACTION_VIEW, started.single().action)
            assertEquals(Uri.parse("tel:0501234567"), started.single().data)

            started.clear()
            val call = lineText("050 123 4567").fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Call 0501234567" }
            compose.runOnUiThread { call.action() }
            compose.waitUntil(5_000) { started.isNotEmpty() }
            assertEquals(Uri.parse("tel:0501234567"), started.single().data)
        } finally {
            instrumentation.removeMonitor(dialer)
        }
    }

    @Test
    fun aCaptionLineOpensItsWholeSavedConversationAtThatLineAndBackReturnsToTheCaptions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val store = TranscriptHistoryStore(File(app.filesDir, CaptionSession.HISTORY_FOLDER))
        val startedAt = System.currentTimeMillis() / 1_000.0 - 600
        val said = (0 until 80).map { index ->
            SavedSegment(UUID.randomUUID(), if (index == 60) "The pharmacy closes early on Friday" else "Small talk about the weather", null, null, startedAt + index, true)
        }
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 90,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = said,
        )
        store.save(record)
        try {
            val held = committed("The pharmacy closes early on Friday").copy(id = said[60].id)
            val unsaved = committed("Off the record")
            showLines(listOf(held, unsaved), conversation = { index -> if (index == 0) record.id else null })
            val open = "Open the whole conversation, to share or search"
            assertTrue(lineText("Off the record").fetchSemanticsNode().config[SemanticsActions.CustomActions].none { it.label == open })

            lineText("The pharmacy closes early").performTouchInput { longClick() }
            compose.onNodeWithText(open).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Share").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { savedLine("The pharmacy closes early on Friday") }
            compose.onNodeWithText("Back").performClick()
            compose.waitUntil(5_000) { displayed("Off the record") }

            val action = lineText("The pharmacy closes early").fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == open }
            compose.runOnUiThread { action.action() }
            compose.waitUntil(5_000) { savedLine("The pharmacy closes early on Friday") && compose.onAllNodesWithText("Share").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Back").performClick()
            compose.waitUntil(5_000) { displayed("Off the record") }
        } finally {
            store.delete(record.id)
        }
    }

    @Test
    fun aCaptionLineAsksThemToRepeatAloudFromItsHoldMenuOrTheScreenReader() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        assertTrue("no voice on the phone for the request", within(15_000) { app.captions.canAskToRepeat() })
        showLines(listOf(committed("What did the doctor say about the pills")))
        val ask = "Ask them to repeat that"
        compose.onNodeWithText("What did the doctor say", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText(ask).performClick()
        assertTrue("never spoke", within(10_000) { app.captions.isSpeaking })
        assertTrue("never finished", within(15_000) { !app.captions.isSpeaking })

        val line = compose.onNodeWithText("What did the doctor say", substring = true).fetchSemanticsNode()
        val action = line.config[SemanticsActions.CustomActions].single { it.label == ask }
        compose.runOnUiThread { action.action() }
        assertTrue("never spoke for the screen reader", within(10_000) { app.captions.isSpeaking })
        assertTrue(within(15_000) { !app.captions.isSpeaking })
    }

    @Test
    fun theSayScreenSaysWhatSheTypesOrTapsStopsItAndKeepsWhatSheTyped() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val phrasesBefore = app.settings.current.value.quickPhrases
        val typed = "Thank you very much for coming to see me today, I will read everything you say on my phone, so please speak slowly and clearly"
        app.settings.change { it.quickPhrases = AppSettings.defaultQuickPhrases(UILanguage.Hebrew) }
        try {
            assertTrue("no voice on the phone", within(15_000) { app.captions.canAskToRepeat() })
            compose.onNodeWithContentDescription("Say something out loud").performClick()
            compose.onNodeWithText("Say something").assertExists()
            compose.onNodeWithText("Slower, please").performScrollTo()
            compose.onNodeWithText("Play").performScrollTo().assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextInput(typed)
            compose.onNodeWithText("Play").performClick()
            assertTrue("never spoke", within(10_000) { app.captions.isSpeaking })
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Stop").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Stop").performClick()
            assertTrue("never stopped", within(1_500) { !app.captions.isSpeaking })
            compose.onNodeWithContentDescription("Play again: $typed").assertExists()
            compose.onNodeWithContentDescription("Add to quick phrases").performClick()
            compose.waitUntil(5_000) { app.settings.current.value.quickPhrases == AppSettings.defaultQuickPhrases(UILanguage.English) + typed }
            compose.onNodeWithContentDescription("Add to quick phrases").assertDoesNotExist()

            compose.onNodeWithText("Slower, please").performScrollTo().performClick()
            assertTrue("the quick phrase was never said", within(10_000) { app.captions.isSpeaking })
            assertTrue(within(15_000) { !app.captions.isSpeaking })

            compose.onNodeWithText("Close").performScrollTo().performClick()
            compose.onNodeWithText("Say something").assertDoesNotExist()
            compose.onNodeWithContentDescription("Say something out loud").performClick()
            compose.onNode(hasSetTextAction()).assert(hasText(typed))
        } finally {
            app.settings.change { it.quickPhrases = phrasesBefore }
        }
    }

    @Test
    fun quickPhrasesAreAddedDeletedAndRestoredOnTheSayScreen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val phrasesBefore = app.settings.current.value.quickPhrases
        val english = AppSettings.defaultQuickPhrases(UILanguage.English)
        val stored = { app.settings.current.value.quickPhrases }
        app.settings.change { it.quickPhrases = AppSettings.defaultQuickPhrases(UILanguage.Hebrew) }
        try {
            compose.onNodeWithContentDescription("Say something out loud").performClick()
            compose.onNodeWithText("Edit").performScrollTo().performClick()
            compose.onNodeWithText("Restore defaults").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithContentDescription("Add").assertIsNotEnabled()
            compose.onNodeWithText("New phrase").performScrollTo().performTextInput("Please write it down for me")
            compose.onNodeWithContentDescription("Add").performClick()
            compose.waitUntil(5_000) { stored() == english + "Please write it down for me" }

            compose.onNodeWithText("Slower, please").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(5_000) { stored() == english - "Slower, please" + "Please write it down for me" }
            val waiting = compose.onNodeWithText("Wait, I didn't catch that").fetchSemanticsNode()
            val delete = waiting.config[SemanticsActions.CustomActions].single { it.label == "Delete" }
            compose.runOnUiThread { delete.action() }
            compose.waitUntil(5_000) { stored() == english - "Slower, please" - "Wait, I didn't catch that" + "Please write it down for me" }

            compose.onNodeWithText("Restore defaults").performScrollTo().performClick()
            compose.onNodeWithText("Phrases that were added or changed will be deleted.").assertExists()
            compose.onNode(hasText("Restore") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { stored() == english }
            compose.onNodeWithText("Restore defaults").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.onNodeWithText("New phrase").assertDoesNotExist()
            compose.onNodeWithText("Slower, please").performScrollTo()
        } finally {
            app.settings.change { it.quickPhrases = phrasesBefore }
        }
    }

    @Test
    fun quickPhrasesMoveUpOrDownFromTheHoldMenuOrTheScreenReaderButNotPastTheEnds() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val phrasesBefore = app.settings.current.value.quickPhrases
        val english = AppSettings.defaultQuickPhrases(UILanguage.English)
        val stored = { app.settings.current.value.quickPhrases }
        val actions = { phrase: String -> compose.onNodeWithText(phrase).fetchSemanticsNode().config[SemanticsActions.CustomActions] }
        app.settings.change { it.quickPhrases = english }
        try {
            compose.onNodeWithContentDescription("Say something out loud").performClick()
            compose.onNodeWithText("Edit").performScrollTo().performClick()
            compose.onNodeWithText(english[1]).performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText("Move up").performClick()
            compose.waitUntil(5_000) { stored() == listOf(english[1], english[0]) + english.drop(2) }

            compose.onNodeWithText(english[1]).performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText("Move up").assertDoesNotExist()
            compose.onNodeWithText("Move down").performClick()
            compose.waitUntil(5_000) { stored() == english }

            assertEquals(listOf("Move down", "Delete"), actions(english.first()).map { it.label })
            assertEquals(listOf("Move up", "Move down", "Delete"), actions(english[1]).map { it.label })
            val moveUp = actions(english.last()).also { last -> assertEquals(listOf("Move up", "Delete"), last.map { it.label }) }.first()
            compose.runOnUiThread { moveUp.action() }
            compose.waitUntil(5_000) { stored() == english.dropLast(2) + english.last() + english[english.size - 2] }

            compose.onNodeWithText("Done").performScrollTo().performClick()
            val said = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes()
                .mapNotNull { node -> runCatching { node.config[SemanticsProperties.Text] }.getOrNull()?.joinToString("")?.takeIf { it in english } }
            assertEquals(english.dropLast(2) + english.last() + english[english.size - 2], said)
        } finally {
            app.settings.change { it.quickPhrases = phrasesBefore }
        }
    }

    @Test
    fun theBigLettersPadShowsTheSayFieldHugeInHerColoursFlipsItForThePersonOppositeAndSaysIt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.display
        val underline = "________"
        try {
            app.settings.change { it.display = it.display.copy(theme = DisplayPreferences.Theme.HighContrast, fontSize = 30.0) }
            assertTrue("no voice on the phone", within(15_000) { app.captions.canAskToRepeat() })
            compose.onNodeWithContentDescription("Say something out loud").performClick()
            compose.waitUntil(5_000) { sayFieldFocused() }
            compose.onNode(hasSetTextAction()).performTextInput("Pharmacy")
            compose.onNodeWithText("Full screen, big letters").performScrollTo().performClick()
            compose.onNodeWithText("Say something").assertDoesNotExist()
            assertEquals(52.sp, layoutOf("Pharmacy").layoutInput.style.fontSize)
            assertEquals(NUMBER_YELLOW, layoutOf("Pharmacy").layoutInput.style.color)
            app.settings.change { it.display = it.display.copy(fontSize = 64.0) }
            compose.waitForIdle()
            assertEquals(64.sp, layoutOf("Pharmacy").layoutInput.style.fontSize)

            compose.onNodeWithText("Speak").performClick()
            assertTrue("never spoke", within(10_000) { app.captions.isSpeaking })
            compose.runOnUiThread { app.captions.stopSpeaking() }

            compose.onNodeWithText("Clear").performClick()
            compose.onNodeWithText("Type here…").assertIsDisplayed()
            compose.onNodeWithText("Clear").assertIsNotEnabled()
            compose.onNodeWithText("Flip").assertIsNotEnabled()
            compose.onNodeWithText("Speak").assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextInput(underline)
            compose.onNodeWithText("Flip").performClick()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            val flipped = compose.onNodeWithText(underline).captureToImage()
            val ink = inkRows(flipped)
            assertTrue("nothing drawn", ink.isNotEmpty())
            assertTrue("drawn the right way up: ink on rows $ink of ${flipped.height}", ink.last() < flipped.height / 2)
            compose.onNodeWithText("Back").performClick()
            compose.onNode(hasSetTextAction()).assert(hasText(underline))

            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Say something").assertExists()
            assertFalse("the keyboard came back over the Say screen", within(1_500) { sayFieldFocused() })
            compose.onNode(hasSetTextAction()).assert(hasText(underline))
            compose.onNodeWithContentDescription("Play again: Pharmacy").assertExists()
            compose.onNodeWithText("Full screen, big letters").performScrollTo().performClick()
            compose.onNodeWithText("Say something").assertDoesNotExist()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Say something").assertExists()
        } finally {
            app.settings.change { it.display = before }
        }
    }

    @Test
    fun whileThePhoneTalksTheCaptionScreensSayButtonStopsItInsteadOfOpening() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        assertTrue("no voice on the phone", within(15_000) { app.captions.canAskToRepeat() })
        compose.runOnUiThread { app.captions.askToRepeat() }
        assertTrue("never spoke", within(10_000) { app.captions.isSpeaking })
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Stop speaking").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Stop speaking").performClick()
        assertTrue("never stopped", within(5_000) { !app.captions.isSpeaking })
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Say something out loud").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Say something").assertDoesNotExist()
    }

    @Test
    fun aSaveThatFailsShowsTheSavingTroubleBannerUntilTappedAwayAndAgainOnlyAfterSavingWorked() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val settingsFile = File(app.filesDir, "ozen-settings.json")
        val settingsBefore = settingsFile.readBytes()
        val folder = File(app.filesDir, CaptionSession.HISTORY_FOLDER)
        val store = TranscriptHistoryStore(folder)
        val savingBefore = app.settings.current.value.saveHistory
        val startedAt = System.currentTimeMillis() / 1_000.0 - 600
        val line = SavedSegment(UUID.randomUUID(), "The pharmacy closes early on Friday", null, null, startedAt, true)
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 60,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = listOf(line),
        )
        val title = "Saving on the phone failed"
        val banner = { compose.onAllNodesWithText(title, substring = true).fetchSemanticsNodes().isNotEmpty() }
        val breakSettings = {
            settingsFile.delete()
            settingsFile.mkdir()
            File(settingsFile, "in the way").writeText("")
        }
        val fixSettings = {
            settingsFile.deleteRecursively()
            settingsFile.writeBytes(settingsBefore)
        }
        val save = { app.settings.change { it.display = it.display.copy() } }
        store.save(record)
        try {
            app.settings.change { it.saveHistory = true }
            assertFalse(within(1_000) { banner() })

            breakSettings()
            save()
            compose.waitUntil(5_000) { banner() }
            compose.onNodeWithText("The phone is probably full. New conversations and settings won't be saved until space frees up. Ask whoever helps you with the phone to free some.", substring = true).assertExists()
            compose.onNodeWithText(title, substring = true).performClick()
            compose.waitUntil(5_000) { !banner() }
            save()
            assertFalse("back while saving still fails", within(1_500) { banner() })
            fixSettings()
            save()
            breakSettings()
            save()
            compose.waitUntil(5_000) { banner() }
            fixSettings()
            save()
            compose.waitUntil(5_000) { !banner() }

            folder.setWritable(false, false)
            app.captions.savedConversations.rename(record.id, "Pharmacy")
            compose.waitUntil(5_000) { banner() }
            app.settings.change { it.saveHistory = false }
            compose.waitUntil(5_000) { !banner() }
            app.settings.change { it.saveHistory = true }
            compose.waitUntil(5_000) { banner() }
            folder.setWritable(true, false)
            app.captions.savedConversations.rename(record.id, "Pharmacy")
            compose.waitUntil(5_000) { !banner() }
            folder.setWritable(false, false)
            app.captions.savedConversations.toggleStar(record.id, line.id)
            compose.waitUntil(5_000) { banner() }
        } finally {
            folder.setWritable(true, false)
            if (settingsFile.isDirectory) fixSettings()
            app.captions.savedConversations.rename(record.id, "")
            app.settings.change { it.saveHistory = savingBefore }
            store.delete(record.id)
        }
    }

    private fun within(millis: Long, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun aWordTheCaptionsGotWrongGoesFirstInTheWordListFromTheLinesHoldMenuOrTheScreenReader() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.vocabulary
        app.settings.change { it.vocabulary = listOf("Dr. Levi") }
        try {
            showLines(listOf(committed("I spoke with Shera yesterday")))
            val fix = "Fix a word for next time"
            lineText("Shera").performTouchInput { longClick() }
            compose.onNodeWithText(fix).performClick()
            compose.onNodeWithText("What the captions showed").assertExists()
            assertEquals(2, compose.onAllNodesWithText("I spoke with Shera yesterday").fetchSemanticsNodes().size)
            compose.onNodeWithText("Add").assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextInput("   ")
            compose.onNodeWithText("Add").assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextInput("Shira")
            compose.onNodeWithText("Add").performClick()
            compose.waitUntil(5_000) { app.settings.current.value.vocabulary == listOf("Shira", "Dr. Levi") }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("What the captions showed").fetchSemanticsNodes().isEmpty() }

            val action = lineText("Shera").fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == fix }
            compose.runOnUiThread { action.action() }
            compose.onNode(hasSetTextAction()).performTextInput("Shai")
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("What the captions showed").fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf("Shira", "Dr. Levi"), app.settings.current.value.vocabulary)
        } finally {
            app.settings.change { it.vocabulary = before }
        }
    }

    @Test
    fun talkStartingAgainAfterAQuietStretchBuzzesWhileTheCaptionsAreOnScreenUnlessSheTurnedItOff() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val wanted = app.settings.current.value.hapticOnSpeechResume
        val buzzed = CopyOnWriteArrayList<AlertVibration>()
        val before = Buzz.play
        Buzz.play = { _, vibration -> buzzed += vibration }
        try {
            app.settings.change { it.hapticOnSpeechResume = true }
            val hello = committed("Hello")
            val dinner = committed("Dinner is ready")
            compose.runOnUiThread {
                CaptionState.clear()
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(hello), now = 100.0)
            }
            compose.waitForIdle()
            assertTrue(buzzed.isEmpty())
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(hello, dinner), now = 110.0)
            }
            compose.waitUntil(5_000) { buzzed.isNotEmpty() }
            assertEquals(listOf(AlertVibration.speechResumed), buzzed)

            compose.onNodeWithText("Settings").performClick()
            val away = committed("Are you there")
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(hello, dinner, away), now = 130.0)
            }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("History").assertExists()
            compose.waitForIdle()
            assertEquals(1, buzzed.size)

            app.settings.change { it.hapticOnSpeechResume = false }
            compose.waitForIdle()
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(hello, dinner, away, committed("Coming")), now = 140.0)
            }
            compose.waitForIdle()
            assertEquals(1, buzzed.size)
        } finally {
            Buzz.play = before
            app.settings.change { it.hapticOnSpeechResume = wanted }
        }
    }

    @Test
    fun aLineWithAnImportantWordIsMarkedAndAFreshMentionBuzzesAndSaysSoForFiveSeconds() {
        val buzzed = CopyOnWriteArrayList<AlertVibration>()
        val before = Buzz.play
        Buzz.play = { _, vibration -> buzzed += vibration }
        try {
            val name = committed("Grandma, dinner is ready")
            val other = committed("Pass the salt")
            compose.runOnUiThread {
                CaptionState.clear()
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(name, other), keywordHits = setOf(name.id))
            }
            compose.waitForIdle()
            assertEquals(1, compose.onAllNodesWithContentDescription("Contains an important word").fetchSemanticsNodes().size)
            assertTrue(buzzed.isEmpty())

            val now = System.currentTimeMillis() / 1_000.0
            compose.runOnUiThread { CaptionState.heard(HeardWord(UUID.randomUUID(), "Doctor", now - 600)) }
            compose.waitForIdle()
            assertTrue(buzzed.isEmpty())
            assertTrue(compose.onAllNodesWithText("Said: Doctor").fetchSemanticsNodes().isEmpty())

            compose.runOnUiThread { CaptionState.heard(HeardWord(UUID.randomUUID(), "Grandma", now)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Said: Grandma").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf(AlertVibration.keyword), buzzed)
            compose.waitUntil(8_000) { compose.onAllNodesWithText("Said: Grandma").fetchSemanticsNodes().isEmpty() }
            assertEquals(1, buzzed.size)
        } finally {
            Buzz.play = before
        }
    }

    @Test
    fun anImportantWordSaidWhileTheAppIsAwayBecomesAPhoneNotificationOnlyThen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val alerts = { manager.activeNotifications.filter { it.notification.channelId == AlertNotifier.CHANNEL_ID } }
        val clearAlerts = { alerts().forEach { manager.cancel(it.tag, it.id) } }
        val before = app.settings.current.value.notifyWhenInBackground
        val line = committed("Grandma, dinner is ready")
        fun hit(alert: UUID) = KeywordHit(segmentID = line.id, match = KeywordMatch(alert, "סבתא", "סבתא", 0), timestamp = System.currentTimeMillis() / 1_000.0)
        fun waitFor(condition: () -> Boolean): Boolean {
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until) {
                if (condition()) return true
                Thread.sleep(100)
            }
            return condition()
        }
        clearAlerts()
        try {
            app.settings.change { it.notifyWhenInBackground = true }
            compose.runOnUiThread { app.captions.wordsHeard(listOf(hit(UUID.randomUUID())), line) }
            assertFalse(waitFor { alerts().isNotEmpty() })

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { app.captions.wordsHeard(listOf(hit(UUID.randomUUID())), line) }
            assertTrue(waitFor { alerts().isNotEmpty() })
            val posted = alerts().single().notification
            assertEquals("Said: Grandma", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Grandma, dinner is ready"))

            clearAlerts()
            assertTrue("the first alert was never taken down", waitFor { alerts().isEmpty() })
            app.settings.change { it.notifyWhenInBackground = false }
            instrumentation.runOnMainSync { app.captions.wordsHeard(listOf(hit(UUID.randomUUID())), line) }
            assertFalse(waitFor { alerts().isNotEmpty() })
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            app.settings.change { it.notifyWhenInBackground = before }
            clearAlerts()
        }
    }

    // The line's own text, which carries its actions; a linked number may have a node of its own.
    private fun lineText(text: String) =
        compose.onAllNodes(hasText(text, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).onFirst()

    private fun copied(): String? {
        var text: String? = null
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        compose.runOnUiThread { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
        return text
    }

    private fun committed(text: String) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)

    private fun showLines(lines: List<TranscriptSegment>, conversation: (Int) -> UUID? = { null }) {
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = lines, conversation = conversation)
        }
        compose.waitForIdle()
    }

    private fun savedLine(text: String) =
        compose.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty() &&
            compose.onNodeWithContentDescription(text, substring = true).isDisplayed()

    private fun displayed(text: String) =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() &&
            compose.onNodeWithText(text, substring = true).isDisplayed()

    private fun showUnsure(text: String, doubtful: List<String>) {
        val line = TranscriptSegment(
            id = UUID.randomUUID(), text = text, isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 0.0,
            confidence = 0.3f, uncertainWords = doubtful,
        )
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(line))
        }
        compose.waitForIdle()
    }

    private fun pixels(image: ImageBitmap) = image.toPixelMap().buffer

    private fun sayFieldFocused() = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }

    private fun inkRows(image: ImageBitmap): List<Int> {
        val pixels = image.toPixelMap()
        return (0 until pixels.height).filter { y -> (0 until pixels.width).any { x -> pixels[x, y].red > 0.5f } }
    }

    private fun darkBarIcons(): Boolean {
        var dark = false
        compose.runOnUiThread {
            val window = compose.activity.window
            dark = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
        }
        return dark
    }

    private fun showLine(text: String, phase: PipelinePhase = PipelinePhase.Paused) {
        val line = TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        compose.runOnUiThread {
            CaptionState.show(phase, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(line))
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
}
