package com.arbelonson.ozen

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptSegment
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

    private fun showLines(lines: List<TranscriptSegment>) {
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = lines)
        }
        compose.waitForIdle()
    }

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
