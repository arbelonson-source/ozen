package com.arbelonson.ozen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
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

    @Test
    fun `a phone number in a finished line links to the dialer, and only when asked`() {
        val line = CaptionLayout.displayText("Call the clinic on 050 123 4567 tomorrow")
        val text = captionText(line, emphasizeNumbers = false, numberColor = null, linkingPhoneNumbers = true)
        assertEquals(line, text.text)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("tel:0501234567", (link.item as LinkAnnotation.Url).url)
        assertEquals("0501234567", line.substring(link.start, link.end).filter { it.isDigit() })
        assertTrue(captionText(line, emphasizeNumbers = false, numberColor = null).getLinkAnnotations(0, line.length).isEmpty())
    }

    @Test
    fun `a tap-to-dial link over a no-break number covers the whole number`() {
        val line = CaptionLayout.displayText("Call 050 123 4567 now", "en")
        val links = captionText(line, emphasizeNumbers = false, numberColor = null, linkingPhoneNumbers = true).getLinkAnnotations(0, line.length)
        val link = links.single()
        assertEquals("tel:0501234567", (link.item as LinkAnnotation.Url).url)
        assertEquals("050${Char(0xA0)}123${Char(0xA0)}4567", line.substring(link.start, link.end))
    }

    @Test
    fun `bold numbers cover the same words once their spaces are no-break`() {
        val plain = "\u05E7\u05D7 3 \u05DB\u05D3\u05D5\u05E8\u05D9\u05DD \u05D1\u05E9\u05E2\u05D4 10:30"
        val joined = CaptionLayout.displayText(plain, "en")
        val before = captionText(plain, emphasizeNumbers = true, numberColor = null).spanStyles.map { it.start to it.end }
        val after = captionText(joined, emphasizeNumbers = true, numberColor = null).spanStyles.map { it.start to it.end }
        assertEquals(before, after)
        assertTrue(before.isNotEmpty())
    }
}
