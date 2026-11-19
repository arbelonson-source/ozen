package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectionMarksTest {
    private val mark = "‏"

    private fun segment(text: String, speaker: String? = null, starred: Boolean = false) =
        SavedSegment(id = UUID.randomUUID(), text = text, speakerName = speaker, speakerClusterID = null, startTimestamp = 0.0, isCommitted = true, isStarred = starred)

    private fun record(segments: List<SavedSegment>, title: String? = null) =
        TranscriptSessionRecord(startedAt = 0.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null, segments = segments, title = title)

    private fun export(vararg segments: SavedSegment) = TranscriptHistoryStore.exportText(record(segments.toList()))

    private fun hit(phrase: String) = KeywordHit(
        segmentID = UUID.randomUUID(),
        match = KeywordMatch(alertID = UUID.randomUUID(), phrase = phrase, matchedText = phrase, wordIndex = 0),
        timestamp = 0.0,
    )

    @Test
    fun `an all-English export has no mark, a Latin name before Hebrew has one`() {
        assertEquals("שיחה מתאריך 01.01.1970\n\n[00:00:00] How did you sleep?\n[00:00:00] Dana: Fine", export(segment("How did you sleep?"), segment("Fine", "Dana")))
        assertTrue(export(segment("OK, נתראה מחר")).endsWith("\n\n$mark[00:00:00] OK, נתראה מחר"))
        assertTrue(export(segment("קח Acamol", "Avi")).endsWith("\n\n$mark[00:00:00] Avi: קח Acamol"))
        assertTrue(export(segment("כדור בבוקר")).endsWith("\n\n[00:00:00] כדור בבוקר"))
        assertTrue(export(segment("10:30")).endsWith("\n\n[00:00:00] 10:30"))
    }

    @Test
    fun `a starred English line has no mark, a Hebrew line opening in English has one`() {
        fun starred(text: String) = TranscriptHistoryStore.exportStarredText(listOf(StarredLine(UUID.randomUUID(), 0.0, segment(text, starred = true))))
        assertEquals("01.01.1970\n[00:00:00] Take it at ten", starred("Take it at ten"))
        assertEquals("01.01.1970\n$mark[00:00:00] OK, מחר", starred("OK, מחר"))
        assertEquals("01.01.1970\n[00:00:00] מחר", starred("מחר"))
    }

    @Test
    fun `an English notification body has no mark, a Hebrew one opening in English has one`() {
        val policy = BackgroundAlertPolicy()
        assertEquals("Someone is at the door", policy.notification(hit("door"), "Someone is at the door", appIsActive = false, now = 0.0)?.body)
        assertEquals("${mark}OK סבתא, בואי", policy.notification(hit("סבתא"), "OK סבתא, בואי", appIsActive = false, now = 100.0)?.body)
    }

    @Test
    fun `a copied line gets the mark only when it opens in English and holds Hebrew`() {
        assertEquals("${mark}OK, נתראה מחר", CaptionLayout.copiedText("OK, נתראה מחר"))
        assertEquals("בשעה 10:30, 3 כדורים", CaptionLayout.copiedText("בשעה 10:30, 3 כדורים"))
        assertEquals("Take 3 pills at 10:30", CaptionLayout.copiedText("Take 3 pills at 10:30"))
        assertEquals("10:30", CaptionLayout.copiedText("10:30"))
        assertEquals("OK\u060C thanks!", CaptionLayout.copiedText("OK\u060C thanks!"))
    }

    @Test
    fun `an Arabic title opening with a Latin word gets the mark, an English one does not`() {
        assertEquals("${mark}WhatsApp مرحبا, 01.01.1970", TranscriptHistoryStore.exportText(record(emptyList(), "WhatsApp مرحبا")))
        assertEquals("Doctor visit, 01.01.1970", TranscriptHistoryStore.exportText(record(emptyList(), "Doctor visit")))
    }
}
