package com.arbelonson.ozen.core

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockChangeTimeTest {
    private val beforeChange = 1_792_881_000.0
    private val afterChange = 1_792_888_200.0
    private val summerOffset = 10_800

    private fun line(text: String, at: Double) = SavedSegment(UUID.randomUUID(), text, null, null, at, isCommitted = true)

    private fun record(zone: String?, offset: Int?) = TranscriptSessionRecord(
        startedAt = beforeChange,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = null,
        inputName = null,
        segments = listOf(line("before", beforeChange), line("after", afterChange)),
        utcOffsetSeconds = offset,
        timeZoneId = zone,
    )

    @Test
    fun anExportShows0130BeforeTheClocksGoBackAnd0230After() {
        val text = TranscriptHistoryStore.exportText(record("Asia/Jerusalem", summerOffset), utcOffsetAt = { 0 }, currentLanguageCode = "en")
        assertTrue(text.contains("[01:30:00] before"), text)
        assertTrue(text.contains("[02:30:00] after"), text)
    }

    @Test
    fun aRecordSavedWithOnlyTheStartingOffsetKeepsReadingByItAsBefore() {
        val text = TranscriptHistoryStore.exportText(record(null, summerOffset), utcOffsetAt = { 0 }, currentLanguageCode = "en")
        assertTrue(text.contains("[01:30:00] before"), text)
        assertTrue(text.contains("[03:30:00] after"), text)
    }

    @Test
    fun aZoneNameThePhoneDoesNotKnowFallsBackToTheStartingOffset() {
        assertEquals(summerOffset, record("Nowhere/Land", summerOffset).utcOffsetSeconds(afterChange))
        assertNull(record("Nowhere/Land", null).utcOffsetSeconds(afterChange))
    }

    @Test
    fun starredLinesAreSharedWithTheOffsetEachLineHad() {
        val source = record("Asia/Jerusalem", summerOffset)
        val lines = source.segments.map { StarredLine(source.id, beforeChange, it, utcOffsetSeconds = summerOffset, timeZoneId = "Asia/Jerusalem") }
        val text = TranscriptHistoryStore.exportStarredText(lines, utcOffsetAt = { 0 })
        assertTrue(text.contains("[01:30:00] before"), text)
        assertTrue(text.contains("[02:30:00] after"), text)
    }

    @Test
    fun theZoneSurvivesSavingAndLoadingAndALaterSaveKeepsTheFirstOne() {
        val store = TranscriptHistoryStore(Files.createTempDirectory("ozen-clock-change").toFile())
        val first = record("Asia/Jerusalem", summerOffset)
        store.save(first)
        assertEquals("Asia/Jerusalem", store.load(first.id)?.timeZoneId)
        store.save(first.copy(timeZoneId = "America/New_York"))
        assertEquals("Asia/Jerusalem", store.load(first.id)?.timeZoneId)
        val plain = record(null, summerOffset)
        store.save(plain)
        assertNull(store.load(plain.id)?.timeZoneId)
        assertFalse(plain.toJson().contains("timeZoneId"))
    }
}
