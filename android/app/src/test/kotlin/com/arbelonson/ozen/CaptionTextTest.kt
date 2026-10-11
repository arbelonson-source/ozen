package com.arbelonson.ozen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.arbelonson.ozen.core.CaptionLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptionTextTest {
    private val shown = CaptionLayout.displayText("\u05E0\u05D9\u05E4\u05D2\u05E9 \u05D1-10:30 \u05D0\u05E6\u05DC \u05D4\u05E8\u05D5\u05E4\u05D0")

    private fun emphasized(text: androidx.compose.ui.text.AnnotatedString) =
        text.spanStyles.map { shown.substring(it.start, it.end).filter { char -> char.isDigit() || char == ':' } }

    @Test
    fun `numbers in a finished line are drawn heavier and in the number colour`() {
        val text = captionText(shown, emphasizeNumbers = true, numberColor = NUMBER_YELLOW)
        assertEquals(shown, text.text)
        assertEquals(listOf("10:30"), emphasized(text))
        assertEquals(FontWeight.ExtraBold, text.spanStyles.single().item.fontWeight)
        assertEquals(NUMBER_YELLOW, text.spanStyles.single().item.color)
    }

    @Test
    fun `a line still being written keeps its own colour for its numbers`() {
        val text = captionText(shown, emphasizeNumbers = true, numberColor = null)
        assertEquals(FontWeight.ExtraBold, text.spanStyles.single().item.fontWeight)
        assertEquals(Color.Unspecified, text.spanStyles.single().item.color)
    }

    @Test
    fun `with bold numbers switched off nothing stands out`() {
        assertTrue(captionText(shown, emphasizeNumbers = false, numberColor = NUMBER_YELLOW).spanStyles.isEmpty())
    }

    @Test
    fun `the words the engine doubted are marked for their dots, the rest of the line is not`() {
        val text = captionText(shown, emphasizeNumbers = false, numberColor = null, doubtfulWords = listOf("\u05D4\u05E8\u05D5\u05E4\u05D0"))
        assertEquals(shown, text.text)
        val marked = text.getStringAnnotations(DOUBTFUL_WORD, 0, text.length).map { shown.substring(it.start, it.end) }
        assertEquals(listOf("\u05D4\u05E8\u05D5\u05E4\u05D0"), marked)
    }

    @Test
    fun `a line without doubted words carries no marks`() {
        val text = captionText(shown, emphasizeNumbers = true, numberColor = NUMBER_YELLOW)
        assertTrue(text.getStringAnnotations(DOUBTFUL_WORD, 0, text.length).isEmpty())
    }
}
