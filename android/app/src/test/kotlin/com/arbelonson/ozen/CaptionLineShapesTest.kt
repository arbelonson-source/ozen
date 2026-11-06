package com.arbelonson.ozen

import com.arbelonson.ozen.core.CaptionConfidence
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.PhoneNumbers
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CaptionLineShapesTest {
    private val whisper = TranscriptionEngineKind.WhisperKit
    private val apple = TranscriptionEngineKind.AppleSpeech

    private fun line(text: String, committed: Boolean = true, confidence: Float? = null) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = committed, startTimestamp = 0.0, lastUpdateTimestamp = 0.0, confidence = confidence)

    private fun expected(segment: TranscriptSegment, languageCode: String, engine: TranscriptionEngineKind) = CaptionLineShapes.Shape(
        text = CaptionLayout.displayText(segment.text, languageCode),
        copied = CaptionLayout.copiedText(segment.text),
        phones = if (segment.isSettled) PhoneNumbers.matches(segment.text) else emptyList(),
        unsure = CaptionConfidence.isUncertain(segment, engine),
        rightToLeft = CaptionLayout.readsRightToLeft(segment.text, languageCode),
    )

    @Test
    fun `only a line that changed is shaped again`() {
        val shapes = CaptionLineShapes()
        val said = (1..200).map { line("שורה $it. אפשר להתקשר ל־052-1234567 אחרי 10:30") }
        val live = line("ועוד", committed = false)
        shapes.shape(said + live, "he", whisper, null)
        assertEquals(201, shapes.made)

        shapes.shape(said + live.copy(text = "ועוד מילה"), "he", whisper, null)
        assertEquals(202, shapes.made)
        shapes.shape(said + live.copy(text = "ועוד מילה"), "he", whisper, null)
        assertEquals(202, shapes.made)
    }

    @Test
    fun `a kept shape is what a fresh one would be, after any change to the line, the language or the engine`() {
        val shapes = CaptionLineShapes()
        val first = line("OK, נתראה מחר בשעה 10:30, תתקשרו ל־052-1234567", confidence = 0.6f)
        repeat(2) { assertEquals(expected(first, "he", whisper), shapes.shape(listOf(first), "he", whisper, null).single()) }

        val fixed = first.copy(text = "OK, נתראה מחר בשעה 11:30, תתקשרו ל־052-7654321")
        assertEquals(expected(fixed, "he", whisper), shapes.shape(listOf(fixed), "he", whisper, null).single())

        assertNotEquals(expected(fixed, "he", whisper).text, expected(fixed, "en", whisper).text)
        assertEquals(expected(fixed, "en", whisper), shapes.shape(listOf(fixed), "en", whisper, null).single())

        assertNotEquals(expected(fixed, "en", whisper).unsure, expected(fixed, "en", apple).unsure)
        assertEquals(expected(fixed, "en", apple), shapes.shape(listOf(fixed), "en", apple, null).single())

        val reopened = fixed.copy(isCommitted = false)
        assertTrue(expected(fixed, "en", apple).phones.isNotEmpty())
        assertEquals(expected(reopened, "en", apple), shapes.shape(listOf(reopened), "en", apple, null).single())
    }

    @Test
    fun `lines no longer shown are let go`() {
        val shapes = CaptionLineShapes()
        val old = line("שורה ישנה")
        val new = line("שורה חדשה")
        shapes.shape(listOf(old, new), "he", whisper, null)
        shapes.shape(listOf(new), "he", whisper, null)
        shapes.shape(listOf(old, new), "he", whisper, null)
        assertEquals(3, shapes.made)
    }
}
