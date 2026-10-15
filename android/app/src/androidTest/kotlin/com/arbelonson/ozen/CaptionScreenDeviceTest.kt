package com.arbelonson.ozen

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arbelonson.ozen.core.AlertFlash
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.SoundEventCatalog
import com.arbelonson.ozen.core.SpeakerProfile
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.BatteryWarning
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.KeywordMatch
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.PipelineFailure
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    fun theNewestLineStaysWhollyInViewWhenTheButtonsComeBackAsOnTheIPhone() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.display
        try {
            app.settings.change { it.display = it.display.copy(autoHideControls = true) }
            val lines = (1..40).map {
                TranscriptSegment(id = UUID.randomUUID(), text = "Line $it of a long talk", isCommitted = true, startTimestamp = it.toDouble(), lastUpdateTimestamp = it.toDouble())
            }
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = lines)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            compose.onNodeWithText("Line 40 of a long talk").assertIsDisplayed()
            compose.onNodeWithContentDescription("Show the buttons").performClick()
            compose.onNodeWithText("Settings").assertExists()
            compose.waitForIdle()
            val newest = compose.onAllNodesWithText("Line 40 of a long talk").fetchSemanticsNodes().singleOrNull()
            assertNotNull("the newest line went out of view when the buttons came back", newest)
            assertEquals("the newest line went under the buttons", newest!!.size.height.toFloat(), newest.boundsInRoot.height, 1f)
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
            words.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("May not have been heard correctly. $hebrew")))
            compose.onNodeWithContentDescription("May not have been heard correctly.").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
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
            compose.onNodeWithText("see you at ten", substring = true)
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
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
    fun turningThePhoneWhileRereadingKeepsThePlaceTheButtonAndTheCountOfNewLines() {
        val lines = (1..50).map { committed("Line $it here") }
        showLines(lines)
        repeat(3) { compose.onNode(hasScrollAction()).performTouchInput { swipeDown() } }
        compose.waitForIdle()
        showLines(lines + (51..52).map { committed("Line $it here") })
        compose.onNodeWithText("2 new lines").assertExists()
        assertFalse(displayed("Line 52 here"))

        val first = compose.activity
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { first.isDestroyed }
        compose.waitForIdle()

        compose.onNodeWithText("2 new lines").assertExists()
        assertFalse(displayed("Line 52 here"))
        assertFalse(displayed("Line 50 here"))
    }

    @Test
    fun turningThePhoneKeepsTheMobileDataQuestion() {
        val waiting = PipelinePhase.Failed(
            PipelineFailure(
                PipelineFailure.Kind.EngineUnavailable,
                "test",
                EngineUnavailability(EngineUnavailability.Kind.WaitingForWiFi, "test", downloadMegabytes = 550),
            ),
        )
        compose.runOnUiThread { CaptionState.show(waiting, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList()) }
        compose.onNodeWithText("Waiting for Wi", substring = true).performClick()
        compose.onNodeWithText("Download the model over cellular data?").assertExists()

        val first = compose.activity
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { first.isDestroyed }
        compose.waitForIdle()

        compose.onNodeWithText("Download the model over cellular data?").assertExists()
        compose.onNodeWithText("Wait for Wi", substring = true).performClick()
        compose.onNodeWithText("Download the model over cellular data?").assertDoesNotExist()
    }

    @Test
    fun theTimeTalkPickedUpIsDrawnAboveALineAfterABreakAndTheAwayMarkTakesItsPlace() {
        val now = System.currentTimeMillis() / 1_000.0
        val before = said("Before the break", at = now - 1_000)
        val after = said("After the break", at = now - 1_000 + CaptionLayout.QUIET_GAP_SECONDS)
        showLines(listOf(before, after))
        val spoken = "After a break, from ${resumedTime(QuietGap(before.lastUpdateTimestamp, after.startTimestamp))}"
        val divider = compose.onNodeWithContentDescription(spoken).assertIsDisplayed().fetchSemanticsNode()
        val line = compose.onNodeWithText("After the break", substring = true).fetchSemanticsNode()
        val first = compose.onNodeWithText("Before the break", substring = true).fetchSemanticsNode()
        assertTrue("divider ${divider.positionInRoot}, line ${line.positionInRoot}", divider.positionInRoot.y + divider.size.height <= line.positionInRoot.y + 1f)
        assertTrue("divider ${divider.positionInRoot}, first ${first.positionInRoot}", divider.positionInRoot.y >= first.positionInRoot.y + first.size.height - 1f)

        compose.runOnUiThread {
            CaptionState.screenLeft(after.startTimestamp - 10)
            CaptionState.show(
                PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null,
                segments = listOf(before, after, said("And more", at = after.startTimestamp + 5)),
            )
            CaptionState.screenReturned(after.startTimestamp + 60)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("From here, what was said while the app was closed: 2 lines").assertIsDisplayed()
        compose.onNodeWithContentDescription(spoken).assertDoesNotExist()
    }

    @Test
    fun manyLinesSaidWhileTheAppWasAwayAreMarkedAndAButtonAtTheTopLeadsUpToThem() {
        val before = (1..3).map { said("Before $it here", at = 50.0 + it) }
        val missed = (1..30).map { said("Missed $it here", at = 150.0 + it) }
        compose.runOnUiThread {
            CaptionState.screenLeft(100.0)
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = before + missed)
            CaptionState.screenReturned(400.0)
        }
        compose.waitForIdle()
        assertTrue(displayed("Missed 30 here"))
        assertFalse(displayed("Missed 1 here"))
        compose.onNodeWithText("What was said meanwhile · 30 lines").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("From here, what was said while the app was closed: 30 lines").assertIsDisplayed()
        assertTrue(displayed("Missed 1 here"))
        compose.onNodeWithText("What was said meanwhile", substring = true).assertDoesNotExist()
        compose.onNodeWithText("To the last line").assertExists()
    }

    @Test
    fun aFewLinesSaidWhileTheAppWasAwayShowTheirMarkWithNoButtonToReachThem() {
        val missed = listOf(said("Missed one", at = 150.0), said("Missed two", at = 160.0))
        compose.runOnUiThread {
            CaptionState.screenLeft(100.0)
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(said("Before", at = 50.0)) + missed)
            CaptionState.screenReturned(400.0)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("From here, what was said while the app was closed: 2 lines").assertIsDisplayed()
        compose.onNodeWithText("What was said meanwhile", substring = true).assertDoesNotExist()
        compose.waitUntil(5_000) { CaptionState.screen.value.away?.offersJump == false }
        compose.onNodeWithContentDescription("From here, what was said while the app was closed: 2 lines").assertIsDisplayed()
    }

    @Test
    fun leavingTheAppForAWhileMarksTheLinesSaidMeanwhileOnComingBack() {
        val before = said("Before we left", at = System.currentTimeMillis() / 1_000.0 - 60)
        showLines(listOf(before))
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val left = System.currentTimeMillis() / 1_000.0
            val missed = listOf(said("Missed one", at = left + 1), said("Missed two", at = left + 2))
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(before) + missed)
            }
            Thread.sleep(16_000)
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("From here, what was said while the app was closed: 2 lines").assertIsDisplayed()
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
        compose.onNodeWithText("Take two pills after dinner", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Marked as important. Take two pills after dinner")))
        compose.onNodeWithContentDescription("Marked as important.").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        assertTrue(CaptionState.screen.value.lines.single { it.id == pills.id }.starred)
        assertFalse(CaptionState.screen.value.lines.single { it.id == phone.id }.starred)

        val line = compose.onNodeWithText("Take two pills after dinner", substring = true).fetchSemanticsNode()
        val unmark = line.config[SemanticsActions.CustomActions].single { it.label == "Remove mark" }
        compose.runOnUiThread { unmark.action() }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Marked as important.").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Take two pills after dinner", substring = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))

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
            // The keyboard the Say screen had up slides away over the bottom
            // row; a press before it has gone lands on the keyboard.
            val window = compose.activity.window
            compose.waitUntil(5_000) {
                compose.runOnUiThread { (ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) == 0 }
            }
            compose.onNodeWithContentDescription("Say something out loud").performClick()
            compose.onNode(hasSetTextAction()).assert(hasText(typed))
        } finally {
            app.settings.change { it.quickPhrases = phrasesBefore }
        }
    }

    @Test
    fun theListeningNotificationShowsTheNewestLineOnTheLockScreenOnlyWhileSheWantsCaptionsThere() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val manager = app.getSystemService(NotificationManager::class.java)
        val listening = { manager.activeNotifications.firstOrNull { it.notification.channelId == ListeningService.CHANNEL_ID }?.notification }
        val before = app.settings.current.value.display
        try {
            app.settings.change { it.display = it.display.copy(lockScreenCaptions = true) }
            compose.runOnUiThread { ListeningService.start(app) }
            val lately = { SessionJournal(File(app.filesDir, CaptionSession.JOURNAL_FILE)).entries().takeLast(6).joinToString(" | ") { it.text } }
            assertTrue("never public: ${listening()?.visibility}, phase ${app.captions.pipeline.phase}, journal ${lately()}", within(10_000) { listening()?.visibility == Notification.VISIBILITY_PUBLIC })

            val text = { listening()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() }
            assertTrue("no captions in it: ${text()}", within(5_000) { text() != null })

            // Marked private, a notification still shows in full on a phone set
            // to show sensitive content when locked: the lines must not be in it.
            app.settings.change { it.display = it.display.copy(lockScreenCaptions = false) }
            assertTrue("still showing ${text()}", within(5_000) { listening() != null && text() == null })
            assertNull(listening()!!.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))

            app.settings.change { it.display = it.display.copy(lockScreenCaptions = true) }
            assertTrue("never back: ${text()}", within(5_000) { text() != null && listening()?.visibility == Notification.VISIBILITY_PUBLIC })
        } finally {
            compose.runOnUiThread { app.captions.pipeline.stop() }
            app.settings.change { it.display = before }
        }
    }

    // Android's speech engine refuses more than a few thousand characters at
    // once, and refused, nothing was said; the iPhone says any length.
    @Test
    fun aReplyLongerThanThePhonesSpeechEngineTakesAtOnceIsStillSaid() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        assertTrue("no voice on the phone", within(15_000) { app.captions.canAskToRepeat() })
        val limit = TextToSpeech.getMaxSpeechInputLength()
        val reply = "Please read this sentence aloud slowly. ".repeat(limit / 40 + 5).trim()
        assertTrue(reply.length > limit)
        try {
            compose.runOnUiThread { app.captions.speak(reply) }
            assertTrue("a reply of ${reply.length} characters was never said", within(10_000) { app.captions.isSpeaking })
        } finally {
            compose.runOnUiThread { app.captions.stopSpeaking() }
        }
        assertTrue("never stopped", within(1_500) { !app.captions.isSpeaking })
    }

    // Said in pieces, a reply is one reply: the Stop button and the paused
    // captions must not see it end between them.
    @Test
    fun aReplySaidInPiecesStaysSpeakingFromTheFirstToTheLast() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        assertTrue("no voice on the phone", within(15_000) { app.captions.canAskToRepeat() })
        val voice = app.captions.voice
        val limitBefore = voice.pieceLimit
        val listener = voice.onSpeakingChanged
        // Every change as it happens: the next piece starts within a few
        // milliseconds, too soon for looking now and then to see a gap.
        val changes = CopyOnWriteArrayList<Boolean>()
        try {
            voice.pieceLimit = 20
            compose.runOnUiThread {
                voice.onSpeakingChanged = { speaking ->
                    changes += speaking
                    listener?.invoke(speaking)
                }
                app.captions.speak("One two three. Four five six. Seven eight nine.")
            }
            assertTrue("never spoke", within(10_000) { app.captions.isSpeaking })
            assertTrue("never finished", within(30_000) { !voice.isBusy && !app.captions.isSpeaking })
            assertEquals("not quiet at the end: $changes", false, changes.lastOrNull())
            assertTrue("seemed to stop between the pieces: $changes", changes.dropLast(1).all { it })
        } finally {
            compose.runOnUiThread {
                voice.onSpeakingChanged = listener
                app.captions.stopSpeaking()
            }
            voice.pieceLimit = limitBefore
        }
    }

    // The lines keep their names either way and the screen leaves them out
    // as it draws, so the locked phone has to leave them out too.
    @Test
    fun theListeningNotificationNamesWhoSpokeOnlyWhileSpeakerNamesAreOn() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val manager = app.getSystemService(NotificationManager::class.java)
        val text = { manager.activeNotifications.firstOrNull { it.notification.channelId == ListeningService.CHANNEL_ID }?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() }
        val before = app.settings.current.value.display
        val now = System.currentTimeMillis() / 1_000.0
        val said = TranscriptSegment(id = UUID.randomUUID(), text = "Names test good morning", isCommitted = true, speakerClusterID = 0, startTimestamp = now, lastUpdateTimestamp = now)
        // The captions publish their own lines as they start, so the line is
        // put back until the notification shows what is expected.
        val shows = { expected: String ->
            within(10_000) {
                compose.runOnUiThread {
                    if (CaptionState.screen.value.lines.none { it.id == said.id }) {
                        CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(said), languageCode = "en", speakerName = { "Dana" })
                    }
                }
                text() == expected
            }
        }
        try {
            app.settings.change { it.display = it.display.copy(showSpeakerNames = true) }
            compose.runOnUiThread { ListeningService.start(app) }
            // With a model on the phone, its seconds of loading publish the
            // captions' own empty lines over and over; looked at once they
            // have started, what the notification shows holds still.
            compose.waitUntil(30_000) { app.captions.pipeline.phase.isListening || app.captions.pipeline.phase is PipelinePhase.Failed }
            assertTrue("not named with names on: ${text()}", shows("Dana: Names test good morning"))
            app.settings.change { it.display = it.display.copy(showSpeakerNames = false) }
            assertTrue("named with names off: ${text()}", shows("Names test good morning"))
        } finally {
            compose.runOnUiThread {
                app.captions.pipeline.stop()
                CaptionState.clear()
            }
            app.settings.change { it.display = before }
        }
    }

    // Android lets an app out of sight use the microphone only while its
    // listening notification is up, so the phone talking must not end it.
    @Test
    fun whileThePhoneTalksTheListeningNotificationStaysAndSaysSoAndOnlyAPauseSheChoseEndsIt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val manager = app.getSystemService(NotificationManager::class.java)
        val listening = { manager.activeNotifications.firstOrNull { it.notification.channelId == ListeningService.CHANNEL_ID }?.notification }
        val title = { listening()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
        val paused = { forSpeech: Boolean ->
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList(), pausedForSpeech = forSpeech)
            }
        }
        try {
            compose.runOnUiThread { ListeningService.start(app) }
            val lately = { SessionJournal(File(app.filesDir, CaptionSession.JOURNAL_FILE)).entries().takeLast(8).joinToString(" | ") { it.text } }
            assertTrue(
                "never shown: phase ${app.captions.pipeline.phase}, busy ${app.captions.voice.isBusy}, screen ${CaptionState.screen.value.phase} ${CaptionState.screen.value.pausedForSpeech}, journal ${lately()}",
                within(10_000) { listening() != null },
            )
            // Without a model here the captions fail and try again on a timer,
            // publishing each try over the screen this test sets; it is set
            // again until the notification has caught up with it.
            assertTrue(
                "not the phone talking: ${title()}",
                within(5_000) {
                    paused(true)
                    title() == "The phone is talking"
                },
            )
            assertFalse("the phone talking took the notification away", within(2_000) { listening() == null })
            paused(false)
            assertTrue("a pause she chose kept it", within(5_000) { listening() == null })
        } finally {
            compose.runOnUiThread {
                app.captions.pipeline.stop()
                CaptionState.clear()
            }
        }
    }

    @Test
    fun withNoHebrewVoiceTheSayScreenSaysSoAndOpensThePhonesVoiceSettings() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val started = CopyOnWriteArrayList<Intent>()
        val settings = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                started += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phrasesBefore = app.settings.current.value.quickPhrases
        assertTrue("the phone's speech engine never answered", within(15_000) { app.captions.voice.installed.value != null })
        app.settings.change { it.quickPhrases = AppSettings.defaultQuickPhrases(UILanguage.Hebrew) }
        instrumentation.addMonitor(settings)
        try {
            compose.runOnUiThread { app.captions.voice.installed.value = emptySet() }
            compose.onNodeWithContentDescription("Say something out loud").performClick()
            keyboardAway()
            compose.onNodeWithText("Slower, please").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("No speech engine or voice is installed on this phone, so phrases can’t be read aloud.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("No Hebrew voice is installed.").assertDoesNotExist()
            compose.runOnUiThread { app.captions.voice.installed.value = setOf(UILanguage.English) }
            compose.waitForIdle()
            compose.onNodeWithText("No Hebrew voice is installed.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Open phone settings").performScrollTo().performClick()
            compose.waitUntil(5_000) { started.isNotEmpty() }
            assertEquals(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, started.first().action)

            compose.runOnUiThread { app.captions.voice.installed.value = setOf(UILanguage.English, UILanguage.Hebrew) }
            compose.waitForIdle()
            compose.onNodeWithText("No Hebrew voice is installed.").assertDoesNotExist()
            compose.onNodeWithText("Open phone settings").assertDoesNotExist()
            compose.onNodeWithText("Slower, please").performScrollTo().assertIsEnabled()
        } finally {
            instrumentation.removeMonitor(settings)
            compose.runOnUiThread { app.captions.refreshVoice() }
            app.settings.change { it.quickPhrases = phrasesBefore }
        }
    }

    @Test
    fun comingBackToTheAppLooksAgainForTheVoicesThePhoneHas() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        assertTrue("the phone's speech engine never answered", within(15_000) { app.captions.voice.installed.value != null })
        val real = app.captions.voice.installed.value
        val stale = setOf(UILanguage.Amharic)
        compose.runOnUiThread { app.captions.voice.installed.value = stale }
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(stale, app.captions.voice.installed.value)
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        assertTrue("still ${app.captions.voice.installed.value}, not $real", within(5_000) { app.captions.voice.installed.value == real })
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
            // In some full runs, and never alone, the ink came out mid-picture
            // (rows 83-100, then 104-121, of 197). Where the words were and
            // whether the keyboard was still up, either side of the picture,
            // is what the next such failure needs to say.
            val window = compose.activity.window
            val scene = {
                val keyboard = compose.runOnUiThread { ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
                "words at ${compose.onNodeWithText(underline).fetchSemanticsNode().boundsInWindow}, keyboard $keyboard px"
            }
            val before = scene()
            val flipped = compose.onNodeWithText(underline).captureToImage()
            val after = scene()
            val ink = inkRows(flipped)
            assertTrue("nothing drawn", ink.isNotEmpty())
            assertTrue("drawn the right way up: ink on rows $ink of ${flipped.height} ($before before the picture, $after after)", ink.last() < flipped.height / 2)
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

    // As on the iPhone, a screen reader says what flipping is for; "Back"
    // needs no such note.
    @Test
    fun theBigLettersFlipButtonTellsAScreenReaderWhatFlippingIsFor() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        assertTrue("no voice on the phone", within(15_000) { app.captions.canAskToRepeat() })
        compose.onNodeWithContentDescription("Say something out loud").performClick()
        compose.waitUntil(5_000) { sayFieldFocused() }
        compose.onNode(hasSetTextAction()).performTextInput("Pharmacy")
        compose.onNodeWithText("Full screen, big letters").performScrollTo().performClick()
        val flip = compose.onNodeWithText("Flip").fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertEquals("Flip the text so the person sitting across from you can read it", flip.label)
        compose.onNodeWithText("Flip").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertNull(compose.onNodeWithText("Back").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithText("Close").performClick()
    }

    // As on the iPhone, a screen reader hears what the big letters are for.
    @Test
    fun theSayScreensBigLettersButtonTellsAScreenReaderWhatItIsFor() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        assertTrue("no voice on the phone", within(15_000) { app.captions.canAskToRepeat() })
        compose.onNodeWithContentDescription("Say something out loud").performClick()
        compose.waitUntil(5_000) { sayFieldFocused() }
        val big = compose.onNodeWithText("Full screen, big letters").performScrollTo()
        assertEquals(
            "Let someone write to you, or show the person you’re talking with what you wrote",
            big.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )
        big.performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Say something").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
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
            compose.onNodeWithText("The phone is probably full. New conversations and settings won't be saved until space frees up. Ask whoever helps you with the phone to free some (Settings → Storage).", substring = true).assertExists()
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

    @Test
    fun aLineStarredInItsSavedConversationShowsOrLosesItsStarOnTheCaptionScreenAtOnce() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val store = TranscriptHistoryStore(File(app.filesDir, CaptionSession.HISTORY_FOLDER))
        val savingBefore = app.settings.current.value.saveHistory
        val startedAt = System.currentTimeMillis() / 1_000.0 - 600
        val said = "The pharmacy closes early on Friday"
        val line = SavedSegment(UUID.randomUUID(), said, null, null, startedAt, true)
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 60,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = listOf(line),
        )
        val starred = { CaptionState.screen.value.lines.single { it.id == line.id }.starred }
        store.save(record)
        try {
            app.settings.change { it.saveHistory = true }
            showLines(listOf(TranscriptSegment(id = line.id, text = said, isCommitted = true, startTimestamp = startedAt, lastUpdateTimestamp = startedAt)))
            compose.runOnUiThread { app.captions.savedConversations.toggleStar(record.id, line.id) }
            compose.waitForIdle()
            assertTrue("the star given in the saved conversation is missing from the caption screen", starred())
            compose.runOnUiThread { app.captions.savedConversations.toggleStar(record.id, line.id) }
            compose.waitForIdle()
            assertFalse("the star taken away in the saved conversation is still on the caption screen", starred())
        } finally {
            app.settings.change { it.saveHistory = savingBefore }
            store.delete(record.id)
        }
    }

    // The Say screen opens with the keyboard coming up; a press while it
    // slides in lands where the button was.
    private fun keyboardAway() {
        val window = compose.activity.window
        val keyboard = { compose.runOnUiThread { ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 } }
        within(3_000) { keyboard() > 0 }
        compose.runOnUiThread { WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5_000) { keyboard() == 0 }
        compose.waitForIdle()
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
    fun aSoundHeardShowsItsBannerUntilTappedAwayAndAgainOnComingBackFromSettingsWithinItsTime() {
        val heardAt = System.currentTimeMillis() / 1_000.0
        val smoke = SoundAlert(event = SoundEventCatalog.event("smoke_detector")!!, confidence = 0.9, timestamp = heardAt)
        compose.runOnUiThread {
            CaptionState.clear()
            CaptionState.sound(smoke)
        }
        compose.onNodeWithContentDescription("Alert: Smoke detector").assertIsDisplayed()
        compose.onNodeWithText("Attention!", useUnmergedTree = true).assertExists()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Alert: Smoke detector").assertIsDisplayed()
        compose.onNodeWithContentDescription("Alert: Smoke detector").performClick()
        compose.onNodeWithContentDescription("Alert: Smoke detector").assertDoesNotExist()
        assertNull(CaptionState.screen.value.sound)

        val door = SoundAlert(event = SoundEventCatalog.event("door_bell")!!, confidence = 0.7, timestamp = heardAt)
        compose.runOnUiThread { CaptionState.sound(door) }
        compose.onNodeWithContentDescription("Alert: Doorbell").assertIsDisplayed()
        compose.onNodeWithText("Heard just now", useUnmergedTree = true).assertExists()
        compose.runOnUiThread { CaptionState.clear() }
        compose.onNodeWithContentDescription("Alert: Doorbell").assertDoesNotExist()
    }

    private fun heardSmoke() = SoundAlert(event = SoundEventCatalog.event("smoke_detector")!!, confidence = 0.9, timestamp = System.currentTimeMillis() / 1_000.0)

    private fun edgeIsRed(): Boolean {
        val edge = compose.onRoot().captureToImage().toPixelMap().let { it[2, it.height / 2] }
        return edge.red > 0.6f && edge.green < 0.3f && edge.blue < 0.3f
    }

    /** The edge every 150 ms of the test clock for six seconds after a smoke alarm is heard: lit or not. */
    private fun edgeAfterSmoke(): List<Boolean> {
        val now = heardSmoke()
        compose.runOnUiThread { CaptionState.soundHeard(now, now) }
        return List(40) {
            compose.mainClock.advanceTimeBy(150)
            edgeIsRed()
        }
    }

    private fun litStretches(edge: List<Boolean>) = edge.indices.count { edge[it] && (it == 0 || !edge[it - 1]) }

    @Test
    fun tappingWaitingForWiFiAsksBeforeDownloadingOverCellularAndDownloadNowLetsTheModelUseIt() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val waiting = PipelinePhase.Failed(
            PipelineFailure(
                PipelineFailure.Kind.EngineUnavailable,
                "test",
                EngineUnavailability(EngineUnavailability.Kind.WaitingForWiFi, "test", downloadMegabytes = 550),
            ),
        )
        try {
            compose.runOnUiThread { CaptionState.show(waiting, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList()) }
            val status = compose.onNodeWithText("Waiting for Wi", substring = true)
            status.performClick()
            compose.onNodeWithText("Download the model over cellular data?").assertExists()
            compose.onNodeWithText("The model is about 550", substring = true).assertExists()
            compose.onNodeWithText("Over cellular data this can cost money", substring = true).assertExists()
            compose.onNodeWithText("Wait for Wi", substring = true).performClick()
            compose.onNodeWithText("Download the model over cellular data?").assertDoesNotExist()
            assertFalse(compose.runOnUiThread { app.captions.pipeline.cellularDownloadApproved })

            status.performClick()
            compose.onNodeWithText("Download now").performClick()
            compose.onNodeWithText("Download the model over cellular data?").assertDoesNotExist()
            compose.waitUntil(5_000) { compose.runOnUiThread { app.captions.pipeline.cellularDownloadApproved } }
        } finally {
            compose.runOnUiThread { CaptionState.clear() }
        }
    }

    @Test
    fun aSafetySoundFlashesTheScreensEdgeRedOnlyWhenHeardWithTheCaptionsShowing() {
        val motion = ReducedMotion.isOn
        ReducedMotion.isOn = { false }
        compose.runOnUiThread { CaptionState.clear() }
        compose.onNodeWithText("Settings").performClick()
        val before = heardSmoke()
        compose.runOnUiThread { CaptionState.soundHeard(before, before) }
        compose.onNodeWithText("Close").assertExists()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("Close").performSemanticsAction(SemanticsActions.OnClick)
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("History").assertExists()
            repeat(12) {
                compose.mainClock.advanceTimeBy(100)
                assertFalse("a sound from before the screen appeared flashed", edgeIsRed())
            }

            val edge = edgeAfterSmoke()
            assertTrue("a smoke alarm never flashed the edge: $edge", edge.take(2).any { it })
            assertEquals("flashes: $edge", AlertFlash.pattern(SoundEvent.Importance.Critical, reduceMotion = false)!!.count, litStretches(edge))
            assertFalse("still flashing long after: $edge", edge.takeLast(5).any { it })
        } finally {
            ReducedMotion.isOn = motion
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { CaptionState.clear() }
        }
    }

    @Test
    fun withAnimationsOffASafetySoundHoldsTheEdgeRedSteadilyInsteadOfFlashing() {
        val motion = ReducedMotion.isOn
        ReducedMotion.isOn = { true }
        compose.runOnUiThread { CaptionState.clear() }
        compose.onNodeWithText("History").assertExists()
        compose.mainClock.autoAdvance = false
        try {
            val edge = edgeAfterSmoke()
            assertEquals("lit stretches: $edge", 1, litStretches(edge))
            assertTrue("lit for only ${edge.count { it }} of every 150 ms: $edge", edge.count { it } >= 24)
            assertFalse("still lit long after: $edge", edge.takeLast(5).any { it })
        } finally {
            ReducedMotion.isOn = motion
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { CaptionState.clear() }
        }
    }

    @Test
    fun talkStartingAgainAfterAQuietStretchBuzzesWhileTheCaptionsAreOnScreenUnlessSheTurnedItOff() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val wanted = app.settings.current.value.hapticOnSpeechResume
        val buzzed = CopyOnWriteArrayList<AlertVibration>()
        val before = Buzz.play
        Buzz.play = { _, vibration, _ -> buzzed += vibration }
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
        Buzz.play = { _, vibration, _ -> buzzed += vibration }
        try {
            val name = committed("Grandma, dinner is ready")
            val other = committed("Pass the salt")
            compose.runOnUiThread {
                CaptionState.clear()
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(name, other), keywordHits = setOf(name.id))
            }
            compose.waitForIdle()
            assertEquals(1, compose.onAllNodesWithContentDescription("Contains an important word").fetchSemanticsNodes().size)
            compose.onNodeWithText("Grandma, dinner is ready", substring = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Contains an important word"))
            compose.onNodeWithContentDescription("Contains an important word").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            assertTrue(buzzed.isEmpty())

            val now = System.currentTimeMillis() / 1_000.0
            compose.runOnUiThread { CaptionState.heard(HeardWord(UUID.randomUUID(), "Doctor", now - 600)) }
            compose.waitForIdle()
            assertTrue(buzzed.isEmpty())
            assertTrue(compose.onAllNodesWithText("Said: Doctor").fetchSemanticsNodes().isEmpty())

            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
            val grandma = KeywordHit(segmentID = name.id, match = KeywordMatch(UUID.randomUUID(), "Grandma", "Grandma", 0), timestamp = now)
            compose.runOnUiThread { app.captions.wordsHeard(listOf(grandma), name) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Said: Grandma").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf(AlertVibration.keyword), buzzed)
            compose.waitUntil(8_000) { compose.onAllNodesWithText("Said: Grandma").fetchSemanticsNodes().isEmpty() }
            assertEquals(1, buzzed.size)
        } finally {
            Buzz.play = before
        }
    }

    @Test
    fun pinchingTheCaptionsWithTwoFingersMakesTheTextBiggerOrSmallerAndKeepsTheSize() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val displayBefore = app.settings.current.value.display
        try {
            app.settings.change { it.display = it.display.copy(fontSize = 30.0) }
            compose.runOnUiThread {
                CaptionState.clear()
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = (1..4).map { committed("Line $it of the conversation") })
            }
            compose.onNodeWithText("Line 1 of the conversation").assertExists()
            val captions = compose.onNode(hasScrollAction())
            captions.performTouchInput { pinch(center + Offset(-30f, 0f), center + Offset(-300f, 0f), center + Offset(30f, 0f), center + Offset(300f, 0f), 400) }
            compose.waitUntil(5_000) { app.settings.current.value.display.fontSize >= 45.0 }
            val bigger = app.settings.current.value.display.fontSize
            captions.performTouchInput { pinch(center + Offset(-300f, 0f), center + Offset(-30f, 0f), center + Offset(300f, 0f), center + Offset(30f, 0f), 400) }
            compose.waitUntil(5_000) { app.settings.current.value.display.fontSize <= bigger - 15.0 }
            assertTrue(app.settings.current.value.display.fontSize >= DisplayPreferences.minimumFontSize)
            compose.onNodeWithText("Text size", substring = true).assertDoesNotExist()
        } finally {
            app.settings.change { it.display = displayBefore }
            compose.runOnUiThread { CaptionState.clear() }
        }
    }

    @Test
    fun anEmptyScreenSaysItIsListeningAndHowToUseItOrHowToGetCaptionsGoingWhileACallHoldsThem() {
        compose.runOnUiThread {
            CaptionState.clear()
            CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList())
        }
        compose.onNodeWithText("Listening.").assertExists()
        compose.onNodeWithText("Press and hold a line to mark it as important or name the speaker", substring = true).assertExists()
        compose.onNodeWithText("pinch with two fingers to make the text bigger or smaller", substring = true).assertExists()
        compose.onNodeWithText("Captions will appear here.").assertDoesNotExist()

        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = true, scheduledRetry = null, segments = emptyList())
        }
        compose.onNodeWithText("Captions will appear here.").assertExists()
        compose.onNodeWithText("You can choose a microphone with the microphone button", substring = true).assertExists()
        compose.onNodeWithText("Listening.").assertDoesNotExist()
        compose.onNodeWithText("pinch with two fingers", substring = true).assertDoesNotExist()
    }

    @Test
    fun aConversationCutOffMinutesAgoIsOfferedOnTheEmptyScreenOpensWithATapAndCanBeTakenAway() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val store = TranscriptHistoryStore(File(app.filesDir, CaptionSession.HISTORY_FOLDER))
        val endedAt = System.currentTimeMillis() / 1_000.0 - 150
        val record = TranscriptSessionRecord(
            startedAt = endedAt - 60,
            endedAt = endedAt,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = null,
            inputName = null,
            segments = listOf(SavedSegment(UUID.randomUUID(), "The pharmacy closes at nine tonight", null, null, endedAt - 60, true)),
        )
        store.save(record)
        try {
            val summary = store.listSummaries().single { it.id == record.id }
            compose.runOnUiThread {
                CaptionState.clear()
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList())
                CaptionState.recentConversation(summary)
            }
            compose.onNodeWithText("The conversation from 2 minutes ago was saved").assertExists()
            compose.onNodeWithText("The pharmacy closes at nine tonight").assertExists()
            compose.onNodeWithText("Have the phone buzz", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Tap to read it").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Share").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Tap to read it").assertDoesNotExist()
            compose.waitUntil(5_000) { savedLine("The pharmacy closes at nine tonight") }
            compose.onNodeWithText("Back").performClick()

            compose.onNodeWithText("Tap to read it").assertExists()
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithText("Tap to read it").assertDoesNotExist()
            compose.waitForIdle()
            Thread.sleep(500)
            assertNull(CaptionState.screen.value.recentConversation)
        } finally {
            store.delete(record.id)
            compose.runOnUiThread { CaptionState.clear() }
        }
    }

    @Test
    fun pastTheLinesKeptTheTopSaysEarlierLinesWereSavedAndOpensThemAtTheLastOneNoLongerShown() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val store = TranscriptHistoryStore(File(app.filesDir, CaptionSession.HISTORY_FOLDER))
        val segments = (1..205).map { committed("Line $it of the long talk") }
        val record = TranscriptSessionRecord(
            startedAt = 0.0,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = null,
            inputName = null,
            segments = segments.map { SavedSegment(it.id, it.text, null, null, it.startTimestamp, true) },
        )
        store.save(record)
        try {
            showLines(segments) { record.id }
            compose.onNode(hasScrollAction()).performScrollToIndex(0)
            compose.onNodeWithText("Earlier lines are no longer shown").assertDoesNotExist()
            compose.onNodeWithText("Earlier lines were saved. Tap to read them").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Share").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { savedLine("Line 5 of the long talk") }
            assertFalse("the conversation opened at its start", savedLine("Line 1 of the long talk"))
            compose.onNodeWithText("Back").performClick()

            showLines(segments)
            compose.onNode(hasScrollAction()).performScrollToIndex(0)
            compose.onNodeWithText("Earlier lines are no longer shown").assertExists()
            compose.onNodeWithText("Earlier lines were saved", substring = true).assertDoesNotExist()
        } finally {
            store.delete(record.id)
        }
    }

    @Test
    fun aLowBatteryShowsABannerThatSaysHowUrgentItIsAndGoesWithATap() {
        try {
            compose.runOnUiThread {
                CaptionState.clear()
                CaptionState.battery(BatteryWarning.Low(19))
            }
            compose.onNodeWithText("Battery at 19%").assertExists()
            compose.onNodeWithText("Consider plugging in a charger.").assertExists()
            compose.onNodeWithText("Battery at 19%").performClick()
            compose.onNodeWithText("Battery at 19%").assertDoesNotExist()
            assertNull(CaptionState.screen.value.battery)

            compose.runOnUiThread { CaptionState.battery(BatteryWarning.Critical(9)) }
            compose.onNodeWithText("Battery at 9%").assertExists()
            compose.onNodeWithText("The phone might shut down mid-conversation. Plug in a charger.").assertExists()
            compose.onNodeWithText("Consider plugging in a charger.").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { CaptionState.clear() }
        }
    }

    @Test
    fun aLowBatteryWhileTheAppIsAwayBecomesAPhoneNotificationOnlyThen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val alerts = { manager.activeNotifications.filter { it.notification.channelId == AlertNotifier.CHANNEL_ID } }
        val clearAlerts = { alerts().forEach { manager.cancel(it.tag, it.id) } }
        val before = app.settings.current.value.notifyWhenInBackground
        fun waitFor(condition: () -> Boolean): Boolean {
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until) {
                if (condition()) return true
                Thread.sleep(100)
            }
            return condition()
        }
        clearAlerts()
        assertTrue("an alert from before was never taken down", waitFor { alerts().isEmpty() })
        try {
            app.settings.change { it.notifyWhenInBackground = true }
            compose.runOnUiThread { app.captions.batteryWarningRaised(BatteryWarning.Low(19)) }
            assertFalse(waitFor { alerts().isNotEmpty() })

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { app.captions.batteryWarningRaised(BatteryWarning.Critical(9)) }
            assertTrue(waitFor { alerts().isNotEmpty() })
            val posted = alerts().single().notification
            assertEquals("Battery at 9%", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("The phone might turn off, and captions and alerts with it. Plug it in.", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

            clearAlerts()
            assertTrue("the first alert was never taken down", waitFor { alerts().isEmpty() })
            app.settings.change { it.notifyWhenInBackground = false }
            instrumentation.runOnMainSync { app.captions.batteryWarningRaised(BatteryWarning.Low(19)) }
            assertFalse(waitFor { alerts().isNotEmpty() })
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            app.settings.change { it.notifyWhenInBackground = before }
            clearAlerts()
        }
    }

    @Test
    fun theSaidPillNamesWhoSaidTheWordWhileSpeakerNamesAreShown() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val displayBefore = app.settings.current.value.display
        val line = committed("Grandma, dinner is ready").copy(speakerClusterID = 1)
        try {
            app.settings.change { it.display = it.display.copy(showSpeakerNames = true) }
            compose.runOnUiThread {
                CaptionState.clear()
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(line), speakerName = { "Dana" })
                CaptionState.heard(HeardWord(UUID.randomUUID(), "Grandma", System.currentTimeMillis() / 1_000.0, line = line.id))
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Dana said: Grandma").fetchSemanticsNodes().isNotEmpty() }

            app.settings.change { it.display = it.display.copy(showSpeakerNames = false) }
            compose.runOnUiThread { CaptionState.heard(HeardWord(UUID.randomUUID(), "Grandma", System.currentTimeMillis() / 1_000.0, line = line.id)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Said: Grandma").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodesWithText("Dana said: Grandma").fetchSemanticsNodes().isEmpty())
        } finally {
            app.settings.change { it.display = displayBefore }
            compose.runOnUiThread { CaptionState.clear() }
        }
    }

    @Test
    fun whileThePhoneTalksTheStatusSaysSoAndATapStopsItInsteadOfOpeningTheMicrophone() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        compose.runOnUiThread { if (app.captions.pipeline.phase != PipelinePhase.Idle) app.captions.pipeline.stop() }
        compose.waitUntil(10_000) { app.captions.pipeline.phase == PipelinePhase.Idle }
        val long = "This is a long sentence the phone reads aloud, slowly enough that there is time to stop it before it ends, and then a few more words."
        assumeTrue("no voice on this phone to say it", app.captions.canSay(long))
        try {
            compose.runOnUiThread { app.captions.speak(long) }
            compose.waitUntil(10_000) { CaptionState.screen.value.speaking }
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList(), pausedForSpeech = true)
            }
            compose.onNodeWithText("The phone is talking").performClick()
            compose.waitUntil(3_000) { !CaptionState.screen.value.speaking }
            Thread.sleep(2_000)
            assertEquals(PipelinePhase.Idle, app.captions.pipeline.phase)
        } finally {
            compose.runOnUiThread {
                app.captions.stopSpeaking()
                if (app.captions.pipeline.phase != PipelinePhase.Idle) app.captions.pipeline.stop()
                CaptionState.clear()
            }
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
        assertTrue("an alert from before was never taken down", waitFor { alerts().isEmpty() })
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

    // As on the iPhone, where the buzz comes from beneath every sheet: her
    // name said while she is in Settings, History or the Say screen.
    @Test
    fun anImportantWordHeardWhileAnotherScreenIsOpenStillBuzzes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val buzzed = CopyOnWriteArrayList<AlertVibration>()
        val before = Buzz.play
        Buzz.play = { _, vibration, _ -> buzzed += vibration }
        val line = committed("Doctor, the results are in")
        val hit = KeywordHit(segmentID = line.id, match = KeywordMatch(UUID.randomUUID(), "Doctor", "Doctor", 0), timestamp = System.currentTimeMillis() / 1_000.0)
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Close").assertExists()
            compose.runOnUiThread { app.captions.wordsHeard(listOf(hit), line) }
            assertTrue("no buzz with Settings open: $buzzed", within(5_000) { AlertVibration.keyword in buzzed })
        } finally {
            Buzz.play = before
            compose.onNodeWithText("Close").performScrollTo().performClick()
        }
    }

    // As on the iPhone: a word that already reached her as a notification
    // does not buzz again as she comes back, and one that never went out
    // (alerts while away off, quiet hours) is news then, however long ago.
    @Test
    fun aWordToldByNotificationWhileAwayStaysQuietOnReturnAndOneNeverToldBuzzes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val clearAlerts = { manager.activeNotifications.filter { it.notification.channelId == AlertNotifier.CHANNEL_ID }.forEach { manager.cancel(it.tag, it.id) } }
        val buzzed = CopyOnWriteArrayList<AlertVibration>()
        val before = Buzz.play
        Buzz.play = { _, vibration, _ -> buzzed += vibration }
        val notifyBefore = app.settings.current.value.notifyWhenInBackground
        val line = committed("Doctor, the results are in")
        fun hit() = KeywordHit(segmentID = line.id, match = KeywordMatch(UUID.randomUUID(), "Doctor", "Doctor", 0), timestamp = System.currentTimeMillis() / 1_000.0)
        try {
            app.settings.change { it.notifyWhenInBackground = true }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { app.captions.wordsHeard(listOf(hit()), line) }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertFalse("a word already told by a notification buzzed again: $buzzed", within(2_000) { AlertVibration.keyword in buzzed })

            app.settings.change { it.notifyWhenInBackground = false }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { app.captions.wordsHeard(listOf(hit()), line) }
            // Longer than the five seconds the word's pill shows for.
            Thread.sleep(6_000)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue("a word nothing told her of never buzzed on her return: $buzzed", within(5_000) { AlertVibration.keyword in buzzed })
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Said: Doctor").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, buzzed.count { it == AlertVibration.keyword })
        } finally {
            Buzz.play = before
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            app.settings.change { it.notifyWhenInBackground = notifyBefore }
            clearAlerts()
        }
    }

    @Test
    fun aWordHeardWhileAwayWithTheAppsNotificationsBlockedBuzzesOnReturn() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val buzzed = CopyOnWriteArrayList<AlertVibration>()
        val before = Buzz.play
        val allowedBefore = AlertNotifier.allowed
        Buzz.play = { _, vibration, _ -> buzzed += vibration }
        AlertNotifier.allowed = { false }
        val notifyBefore = app.settings.current.value.notifyWhenInBackground
        val line = committed("Doctor, the results are in")
        val hit = KeywordHit(segmentID = line.id, match = KeywordMatch(UUID.randomUUID(), "Doctor", "Doctor", 0), timestamp = System.currentTimeMillis() / 1_000.0)
        try {
            app.settings.change { it.notifyWhenInBackground = true }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { app.captions.wordsHeard(listOf(hit), line) }
            Thread.sleep(6_000)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue("a word no notification could show never buzzed on her return: $buzzed", within(5_000) { AlertVibration.keyword in buzzed })
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Said: Doctor").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            Buzz.play = before
            AlertNotifier.allowed = allowedBefore
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            app.settings.change { it.notifyWhenInBackground = notifyBefore }
        }
    }

    @Test
    fun theStatusShowsWhenItIsBusyAndHowFarADownloadIsAndIsNoButtonWhenATapWouldDoNothing() {
        fun show(phase: PipelinePhase) {
            compose.runOnUiThread { CaptionState.show(phase, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList()) }
            compose.waitForIdle()
        }
        fun status() = compose.onNode(hasText(CaptionState.screen.value.status.title) and hasClickAction())
        val busy = compose.onNodeWithTag(STATUS_BUSY, useUnmergedTree = true)

        show(PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.42, detail = "small")))
        status().assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.42f, 0f..1f))).assertIsNotEnabled()
        busy.assertExists()

        show(PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "small")))
        status().assertIsNotEnabled()
        busy.assertExists()

        show(PipelinePhase.Listening)
        status().assertIsEnabled()
        busy.assertDoesNotExist()
    }

    // A link opened from another app's task can start a second caption
    // screen; Android starts it before it stops the first one.
    @Test
    fun aSecondCaptionScreenOpeningOverTheFirstKeepsTheAppOnScreen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitUntil(5_000) { app.captions.appIsActive }
        val first = compose.activity
        // Android hands a start to the caption screen when it is on top, so
        // the link comes while a small window of another app is in front of
        // it, the caption screen still showing behind, and opens in a task
        // of its own, as a link in another app's window over Ozen can.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        var opened: Activity? = null
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync { opened = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity && it !== first } }
            opened != null
        }
        val second = opened!!
        try {
            compose.waitUntil(5_000) { first.lifecycle.currentState == Lifecycle.State.CREATED }
            instrumentation.waitForIdleSync()
            assertTrue("the app counted as away while the second screen showed", app.captions.appIsActive)
        } finally {
            instrumentation.runOnMainSync { second.finish() }
            compose.waitUntil(5_000) { second.isDestroyed }
            // Android brings the caption screen back started behind the
            // other window, a step the scenario does not count as settled,
            // so Back closes that window instead of the scenario.
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(5_000) { first.lifecycle.currentState == Lifecycle.State.RESUMED }
        }
        compose.waitUntil(5_000) { app.captions.appIsActive }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { !app.captions.appIsActive }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test
    fun turningThePhoneRebuildsTheScreenWithoutCountingAsLeavingTheApp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        compose.waitUntil(5_000) { app.captions.appIsActive }
        val journal = SessionJournal(File(app.filesDir, CaptionSession.JOURNAL_FILE))
        val leaves = { journal.entries().count { it.text.startsWith("app left the screen") } }
        val before = leaves()
        val first = compose.activity
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { first.isDestroyed }
        compose.waitForIdle()
        assertTrue(app.captions.appIsActive)
        assertEquals("turning the phone counted as leaving the app", before, leaves())
    }

    // The line's own text, which carries its actions; a linked number may have a node of its own.
    @Test
    fun aSpeakersNameHeadsTheirRunOfLinesInTheirColourIsSaidOnEveryLineAndGoesWithNamesOff() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.display
        val voice = { text: String, cluster: Int, at: Double ->
            TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, speakerClusterID = cluster, startTimestamp = at, lastUpdateTimestamp = at)
        }
        val names = mapOf(0 to "Dana", 1 to "Speaker 2")
        try {
            app.settings.change { it.display = it.display.copy(theme = DisplayPreferences.Theme.Dark, showSpeakerNames = true) }
            compose.runOnUiThread {
                CaptionState.show(
                    PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null,
                    segments = listOf(voice("Good morning", 0, 0.0), voice("How did you sleep", 0, 1.0), voice("Fine thanks", 1, 2.0)),
                    speakerName = { names[it.speakerClusterID] },
                )
            }
            compose.waitForIdle()
            val dana = compose.onAllNodesWithText("Dana", useUnmergedTree = true)
            assertEquals(1, dana.fetchSemanticsNodes().size)
            dana.onFirst().onParent().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            val label = dana.onFirst().fetchSemanticsNode().boundsInRoot
            val first = lineText("Good morning").fetchSemanticsNode().boundsInRoot
            assertTrue("label $label, line $first", label.bottom <= first.top + 1f)
            assertEquals(SpeakerColour.of(0, "Dana", light = false), labelColour("Dana"))
            assertEquals(SpeakerColour.of(1, "Speaker 2", light = false), labelColour("Speaker 2"))
            lineText("How did you sleep").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Dana: How did you sleep")))
            lineText("Fine thanks").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Speaker 2: Fine thanks")))

            app.settings.change { it.display = it.display.copy(showSpeakerNames = false) }
            compose.waitForIdle()
            compose.onNodeWithText("Dana", useUnmergedTree = true).assertDoesNotExist()
            lineText("How did you sleep").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        } finally {
            app.settings.change { it.display = before }
        }
    }

    @Test
    fun everySavedPersonCanBeReachedInWhoIsSpeakingAtThePhonesLargestTextSize() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.speakerProfiles
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        val names = listOf("Grandma", "Grandpa", "Yossi", "Dana", "Noa", "Avi", "Michal", "Uncle Dov")
        val voiced = TranscriptSegment(id = UUID.randomUUID(), text = "Good morning", isCommitted = true, speakerClusterID = 0, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        try {
            app.settings.change { settings ->
                settings.speakerProfiles = names.mapIndexed { index, name -> SpeakerProfile(name = name, embedding = FloatArray(512) { 0.1f * (index + 1) }) }
            }
            shell("settings put system font_scale 2.0")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale == 2f }
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(voiced), speakerName = { "Speaker 1" })
            }
            lineText("Good morning").performTouchInput { longClick() }
            compose.onNodeWithText("Who is speaking?").performScrollTo().performClick()
            compose.onNodeWithText("Name the speaker").assertExists()
            compose.onNodeWithText("Uncle Dov").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
        } finally {
            shell("settings put system font_scale 1.0")
            app.settings.change { it.speakerProfiles = before }
        }
    }

    @Test
    fun whoIsSpeakingAsksForANameOffersEachSavedPersonOnceAndWaitsForAVoiceOnALineWithoutOne() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.speakerProfiles
        val who = "Who is speaking?"
        val voiced = TranscriptSegment(id = UUID.randomUUID(), text = "Good morning", isCommitted = true, speakerClusterID = 0, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        val unvoiced = TranscriptSegment(id = UUID.randomUUID(), text = "Hello there", isCommitted = true, startTimestamp = 1.0, lastUpdateTimestamp = 1.0)
        try {
            app.settings.change {
                it.speakerProfiles = listOf(
                    SpeakerProfile(name = "Grandma", embedding = FloatArray(512) { 0.1f }),
                    SpeakerProfile(name = "Grandma", embedding = FloatArray(512) { 0.2f }),
                    SpeakerProfile(name = "Yossi", embedding = FloatArray(512) { 0.3f }),
                )
            }
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(voiced, unvoiced), speakerName = { "Speaker 1" })
            }
            lineText("Good morning").performTouchInput { longClick() }
            compose.onNodeWithText(who).performClick()
            compose.onNodeWithText("Name the speaker").assertExists()
            compose.onNodeWithText("From now on, everything this voice says will show with this name.").assertExists()
            compose.onNodeWithText("Saved speakers").assertExists()
            assertEquals(1, compose.onAllNodesWithText("Grandma").fetchSemanticsNodes().size)
            compose.onNodeWithText("Yossi").assertExists()
            compose.onNodeWithText("Save").assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextInput("Dana")
            compose.onNodeWithText("Save").assertIsEnabled()
            compose.onNodeWithText("Yossi").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Name the speaker").fetchSemanticsNodes().isEmpty() }

            val action = lineText("Hello there").fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == who }
            compose.runOnUiThread { action.action() }
            compose.onNodeWithText("No voice has been identified for this line yet. Try again after the person talks a bit more.").assertExists()
            compose.onNodeWithText("Saved speakers").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).performTextInput("Dana")
            compose.onNodeWithText("Save").assertIsNotEnabled()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Name the speaker").fetchSemanticsNodes().isEmpty() }
        } finally {
            app.settings.change { it.speakerProfiles = before }
        }
    }

    @Test
    fun turningThePhoneKeepsFixAWordAndNameTheSpeakerOpenWithWhatWasTyped() {
        val voiced = TranscriptSegment(id = UUID.randomUUID(), text = "Good evening", isCommitted = true, speakerClusterID = 0, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(voiced), speakerName = { "Speaker 1" })
        }
        lineText("Good evening").performTouchInput { longClick() }
        compose.onNodeWithText("Fix a word for next time").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Shira")
        turnThePhone()
        compose.onNodeWithText("What the captions showed").assertExists()
        compose.onNode(hasSetTextAction()).assert(hasText("Shira"))
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What the captions showed").fetchSemanticsNodes().isEmpty() }

        lineText("Good evening").performTouchInput { longClick() }
        compose.onNodeWithText("Who is speaking?").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Dana")
        turnThePhone()
        compose.onNodeWithText("Name the speaker").assertExists()
        compose.onNode(hasSetTextAction()).assert(hasText("Dana"))
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Name the speaker").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aLineWithHebrewInItIsLaidOutFromTheRightEvenWhenItsFirstWordIsEnglish() {
        val line = TranscriptSegment(id = UUID.randomUUID(), text = "OK, אז נתראה", isCommitted = true, speakerClusterID = 0, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(line), speakerName = { "Dana" }, languageCode = "en")
        }
        compose.waitForIdle()
        val name = compose.onNodeWithText("Dana").fetchSemanticsNode().boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().size.width
        assertTrue("the speaker's name sits on the left of a Hebrew line: $name of $width", name.left > width / 2f)
    }

    @Test
    fun withThePhoneAskingForMoreContrastWordsStillBeingHeardAreDrawnAsStrongAsFinishedOnes() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        }
        val uiMode = app.getSystemService(UiModeManager::class.java)
        val words = "Still being heard"
        val brightest = {
            compose.onNodeWithText(words, substring = true).captureToImage().toPixelMap().let { p ->
                (0 until p.width).maxOf { x -> (0 until p.height).maxOf { y -> p[x, y].red } }
            }
        }
        val displayBefore = app.settings.current.value.display
        try {
            app.settings.change { it.display = it.display.copy(theme = DisplayPreferences.Theme.Dark) }
            val line = TranscriptSegment(id = UUID.randomUUID(), text = words, isCommitted = false, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(line))
            }
            compose.waitForIdle()
            assertTrue("words still being heard are dimmed with the phone's contrast left alone", brightest() < 0.9f)
            shell("settings put secure contrast_level 1.0")
            compose.waitUntil(5_000) { uiMode.contrast > 0f }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5_000) { brightest() > 0.97f }
        } finally {
            shell("settings delete secure contrast_level")
            app.settings.change { it.display = displayBefore }
        }
    }

    @Test
    fun turningThePhoneWhileEditingQuickPhrasesKeepsTheKeyboardOffTheEditor() {
        compose.onNodeWithContentDescription("Say something out loud").performClick()
        compose.waitUntil(5_000) { sayFieldFocused() }
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.waitUntil(5_000) { !anyFieldFocused() }
        turnThePhone()
        compose.onNodeWithText("Done").performScrollTo()
        assertFalse("the keyboard came back over the phrase editor", within(1_500) { anyFieldFocused() })
    }

    @Test
    fun turningThePhoneKeepsTheQuestionAboutRestoringThePhrasesOpen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val phrasesBefore = app.settings.current.value.quickPhrases
        app.settings.change { it.quickPhrases = AppSettings.defaultQuickPhrases(UILanguage.English) + "Please write it down for me" }
        try {
            compose.onNodeWithContentDescription("Say something out loud").performClick()
            compose.onNodeWithText("Edit").performScrollTo().performClick()
            compose.onNodeWithText("Restore defaults").performScrollTo().performClick()
            compose.onNodeWithText("Phrases that were added or changed will be deleted.").assertExists()
            turnThePhone()
            compose.onNodeWithText("Phrases that were added or changed will be deleted.").assertExists()
            compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()
            assertEquals(AppSettings.defaultQuickPhrases(UILanguage.English) + "Please write it down for me", app.settings.current.value.quickPhrases)
        } finally {
            app.settings.change { it.quickPhrases = phrasesBefore }
        }
    }

    @Test
    fun fixAWordAndNameTheSpeakerStayOpenWithWhatWasTypedWhenTheirLineLeavesTheScreensNewest200() {
        val voiced = TranscriptSegment(id = UUID.randomUUID(), text = "Good evening", isCommitted = true, speakerClusterID = 0, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        val later = (1..200).map { committed("Later line $it") }
        fun show(segments: List<TranscriptSegment>) = compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = segments, speakerName = { "Speaker 1" })
        }
        show(listOf(voiced))
        lineText("Good evening").performTouchInput { longClick() }
        compose.onNodeWithText("Fix a word for next time").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Shira")
        show(listOf(voiced) + later)
        compose.waitForIdle()
        compose.onNodeWithText("What the captions showed").assertExists()
        compose.onNode(hasSetTextAction()).assert(hasText("Shira"))
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What the captions showed").fetchSemanticsNodes().isEmpty() }

        show(listOf(voiced))
        lineText("Good evening").performTouchInput { longClick() }
        compose.onNodeWithText("Who is speaking?").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Dana")
        show(listOf(voiced) + later)
        compose.waitForIdle()
        compose.onNodeWithText("Name the speaker").assertExists()
        compose.onNode(hasSetTextAction()).assert(hasText("Dana"))
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Name the speaker").fetchSemanticsNodes().isEmpty() }
    }

    private fun turnThePhone() {
        val first = compose.activity
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { first.isDestroyed }
        compose.waitForIdle()
    }

    private fun labelColour(name: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

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

    private fun said(text: String, at: Double) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, startTimestamp = at, lastUpdateTimestamp = at)

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

    private fun anyFieldFocused() =
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

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

    // Older people often set the phone's largest text: "Settings" broke a
    // few letters to a line there. The iPhone keeps each word on one line.
    @Test
    fun theButtonsAtTheBottomKeepEachWordOnOneLineAtThePhonesLargestTextSize() {
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            shell("settings put system font_scale 2.0")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale == 2f }
            compose.waitForIdle()
            for (label in listOf("History", "Say", "Settings")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals("$label broke over lines", 1, layout.lineCount)
                assertFalse("$label was cut off", layout.isLineEllipsized(0))
            }
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }

    // German's words on the bottom row are among the longest: with each
    // button's padding taking its share, "Verlauf" and "Sagen" were cut to
    // "Verla…" and "Sag…" at the phone's largest text.
    @Test
    fun theButtonsAtTheBottomKeepEachGermanWordWholeAtThePhonesLargestTextSize() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val language = app.settings.current.value.appLanguage
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            app.settings.change { it.appLanguage = AppLanguage.German }
            shell("settings put system font_scale 2.0")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale == 2f }
            compose.waitForIdle()
            for (label in listOf("Verlauf", "Sagen", "Einstellungen")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals("$label broke over lines", 1, layout.lineCount)
                assertFalse("$label was cut off", layout.isLineEllipsized(0))
            }
        } finally {
            shell("settings put system font_scale 1.0")
            app.settings.change { it.appLanguage = language }
        }
    }

    // A screen's buttons squeezed its title at the phone's largest text
    // until a word broke: Hebrew's History left its last letter on a line
    // of its own, German's broke into three pieces.
    @Test
    fun screenTitlesKeepEachWordWholeAtThePhonesLargestTextInHebrewAndGerman() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val language = app.settings.current.value.appLanguage
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        val screens = mapOf(
            AppLanguage.Hebrew to listOf("היסטוריה", "הגדרות"),
            AppLanguage.German to listOf("Verlauf", "Einstellungen"),
        )
        try {
            shell("settings put system font_scale 2.0")
            for ((appLanguage, titles) in screens) {
                app.settings.change { it.appLanguage = appLanguage }
                compose.activityRule.scenario.recreate()
                compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale == 2f }
                for (title in titles) {
                    compose.onAllNodesWithText(title).onFirst().performClick()
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNode(hasText(title) and isHeading()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    val broken = (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }.filter { it in 1 until title.length && !title[it - 1].isWhitespace() && !title[it].isWhitespace() }
                    assertTrue("the title $title broke inside a word at $broken", broken.isEmpty())
                    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    compose.waitForIdle()
                }
            }
        } finally {
            shell("settings put system font_scale 1.0")
            app.settings.change { it.appLanguage = language }
        }
    }

    @Test
    fun theOfferToBuzzForHerNameCanBeReadToTheEndAtThePhonesLargestTextSize() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.copy()
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            compose.runOnUiThread { CaptionState.clear() }
            app.settings.change {
                it.keywordAlerts = emptyList()
                it.nameAlertOfferDismissed = false
            }
            shell("settings put system font_scale 2.0")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale == 2f }
            compose.waitForIdle()
            val offer = compose.onNodeWithText("Tap to write your name", substring = true)
            offer.performScrollTo()
            val node = offer.fetchSemanticsNode()
            assertEquals("the offer is cut off", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
        } finally {
            shell("settings put system font_scale 1.0")
            app.settings.change {
                it.keywordAlerts = before.keywordAlerts
                it.nameAlertOfferDismissed = before.nameAlertOfferDismissed
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
}
