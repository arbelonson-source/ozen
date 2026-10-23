package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTranscriptRecordTest {
    private val lines = listOf(TranscribedLine(0.0, 2.0, "first"), TranscribedLine(10.5, 12.0, "second"))

    @Test
    fun theFileNameBecomesTheTitleWithoutItsExtension() {
        val record = FileTranscriptRecord.make("Lecture 4.mp3", lines, startedAt = 1_000.0, endedAt = 1_100.0, modelVariant = "small")
        assertEquals("Lecture 4", record.title)
    }

    @Test
    fun aNameWithOnlyAnExtensionOrNothingGetsNoTitle() {
        assertNull(FileTranscriptRecord.make(null, lines, 0.0, 1.0, null).title)
        assertNull(FileTranscriptRecord.make("  ", lines, 0.0, 1.0, null).title)
    }

    @Test
    fun aNameWithDotsKeepsAllButTheLastPart() {
        assertEquals("PTT-2026.10.16-WA0003", FileTranscriptRecord.make("PTT-2026.10.16-WA0003.opus", lines, 0.0, 1.0, null).title)
    }

    @Test
    fun everyLineIsSavedCommittedAtItsOffsetFromTheStart() {
        val record = FileTranscriptRecord.make("a.wav", lines, startedAt = 5_000.0, endedAt = 5_060.0, modelVariant = "base")
        assertEquals(listOf("first", "second"), record.segments.map { it.text })
        assertEquals(listOf(5_000.0, 5_010.5), record.segments.map { it.startTimestamp })
        assertTrue(record.segments.all { it.isCommitted && it.speakerName == null })
        assertEquals(5_060.0, record.endedAt)
        assertEquals("base", record.modelVariant)
        assertEquals(TranscriptionEngineKind.WhisperKit, record.engine)
    }

    @Test
    fun linesComeBackFromTheSavedConversationInOrder() {
        val record = FileTranscriptRecord.make("a.wav", lines, startedAt = 5_000.0, endedAt = 5_060.0, modelVariant = null)
        assertEquals(lines.map { it.text to it.startSeconds }, FileTranscriptRecord.lines(record).map { it.text to it.startSeconds })
    }

    @Test
    fun aBlankLineIsLeftOut() {
        val record = FileTranscriptRecord.make("a.wav", lines + TranscribedLine(20.0, 21.0, "   "), 0.0, 1.0, null)
        assertEquals(2, record.segments.size)
    }

    @Test
    fun eachSaveGetsItsOwnId() {
        val one = FileTranscriptRecord.make("a.wav", lines, 0.0, 1.0, null)
        val two = FileTranscriptRecord.make("a.wav", lines, 0.0, 1.0, null)
        assertTrue(one.id != two.id)
        val fixed = UUID.randomUUID()
        assertEquals(fixed, FileTranscriptRecord.make("a.wav", lines, 0.0, 1.0, null, id = fixed).id)
    }
}
