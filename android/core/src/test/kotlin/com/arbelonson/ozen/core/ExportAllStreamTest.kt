package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportAllStreamTest {
    private fun record(startedAt: Double, text: String, confidence: Float = 1f) = TranscriptSessionRecord(
        id = UUID.randomUUID(),
        startedAt = startedAt,
        endedAt = null,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = "small",
        inputName = null,
        segments = listOf(
            SavedSegment(UUID.randomUUID(), text, "דנה", null, startedAt, true).copy(confidence = confidence),
        ),
    )

    private val shuffled = listOf(
        record(176_400.0, "שלוש 3.5", 0.7f),
        record(3_661.0, "אחת"),
        record(90_000.0, "שתיים 2.5", 0.6f),
        record(500_000.0, "ארבע"),
    )

    @Test
    fun `streaming the oldest first gives exactly the text of exportAllText`() {
        for (marks in listOf(true, false)) {
            for (comma in listOf(true, false)) {
                val out = StringBuilder()
                val written = TranscriptHistoryStore.exportAll(shuffled.sortedBy { it.startedAt }.asSequence(), { 0 }, marks, comma, out)
                assertTrue(written)
                assertEquals(TranscriptHistoryStore.exportAllText(shuffled, { 0 }, marks, comma), out.toString())
            }
        }
    }

    @Test
    fun `the streamed text has the heading, the rule between neighbours and none after the last`() {
        val two = listOf(record(3_661.0, "אחת"), record(90_000.0, "שתיים"))
        val out = StringBuilder()
        TranscriptHistoryStore.exportAll(two.asSequence(), { 0 }, false, false, out)
        val rule = "\n\n──────────\n\n"
        val expected = "כל השיחות מתוך אוזן\n\n" + TranscriptHistoryStore.exportText(two[0], { 0 }, false, false) +
            rule + TranscriptHistoryStore.exportText(two[1], { 0 }, false, false)
        assertEquals(expected, out.toString())
    }

    @Test
    fun `each conversation is written before the next one is taken from the sequence`() {
        val ordered = shuffled.sortedBy { it.startedAt }
        val out = StringBuilder()
        var taken = 0
        val lazy = sequence {
            for ((k, record) in ordered.withIndex()) {
                if (k > 0) {
                    val earlier = TranscriptHistoryStore.exportText(ordered[k - 1], { 0 }, true, false)
                    assertTrue(out.endsWith(earlier), "conversation ${k - 1} was not written before conversation $k was taken")
                }
                taken++
                yield(record)
            }
        }
        assertTrue(TranscriptHistoryStore.exportAll(lazy, { 0 }, true, false, out))
        assertEquals(ordered.size, taken)
    }

    @Test
    fun `no conversations writes nothing and says so`() {
        val out = StringBuilder()
        assertFalse(TranscriptHistoryStore.exportAll(emptySequence(), { 0 }, true, false, out))
        assertEquals("", out.toString())
    }
}
