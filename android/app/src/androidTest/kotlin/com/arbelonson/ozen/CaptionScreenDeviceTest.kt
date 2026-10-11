package com.arbelonson.ozen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptSegment
import java.util.UUID
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

    private fun darkBarIcons(): Boolean {
        var dark = false
        compose.runOnUiThread {
            val window = compose.activity.window
            dark = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
        }
        return dark
    }

    private fun showLine(text: String) {
        val line = TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Paused, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = listOf(line))
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
}
