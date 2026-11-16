package com.arbelonson.ozen.core

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationTimeZoneTest {
    private val halfPastMidnightInTelAviv = 1_794_695_400.0
    private val telAviv = 7_200
    private val london = 0

    private fun record(offset: Int?, id: UUID = UUID.randomUUID()) = TranscriptSessionRecord(
        id = id,
        startedAt = halfPastMidnightInTelAviv,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = null,
        inputName = null,
        segments = listOf(SavedSegment(UUID.randomUUID(), "hello", null, null, halfPastMidnightInTelAviv, isCommitted = true)),
        utcOffsetSeconds = offset,
    )

    private fun store() = TranscriptHistoryStore(Files.createTempDirectory("ozen-zone").toFile())

    @Test
    fun anExportReadInAnotherZoneShowsTheConversationsOwnClockTimesAndDate() {
        val text = TranscriptHistoryStore.exportText(record(telAviv), utcOffsetAt = { london }, currentLanguageCode = "en")
        assertTrue(text.contains("[00:30:00] hello"), text)
        assertTrue(text.contains("15.11.2026"), text)
        assertFalse(text.contains("14.11.2026"), text)
    }

    @Test
    fun aRecordWithoutAnOffsetIsReadInThePhonesZoneAsBefore() {
        val text = TranscriptHistoryStore.exportText(record(null), utcOffsetAt = { london }, currentLanguageCode = "en")
        assertTrue(text.contains("[22:30:00] hello"), text)
        assertTrue(text.contains("14.11.2026"), text)
    }

    @Test
    fun historyGroupsByTheDayTheConversationHappenedOn() {
        val own = TranscriptSessionSummary.summarizing(record(telAviv))
        val old = TranscriptSessionSummary.summarizing(record(null))
        val now = halfPastMidnightInTelAviv + 3_600
        val ownDays = HistoryDays.grouped(listOf(own), now) { london }
        val oldDays = HistoryDays.grouped(listOf(old), now) { london }
        assertEquals(oldDays[0].id + 1, ownDays[0].id)
    }

    @Test
    fun theOffsetSurvivesSavingAndLoadingAndAnOldRecordStillLoads() {
        val store = store()
        val kept = record(telAviv)
        store.save(kept)
        assertEquals(telAviv, store.load(kept.id)?.utcOffsetSeconds)
        assertEquals(telAviv, store.listSummaries().single().utcOffsetSeconds)
        val plain = record(null)
        assertFalse(plain.toJson().contains("utcOffsetSeconds"))
        assertNull(TranscriptSessionRecord.fromJson(plain.toJson()).utcOffsetSeconds)
        val damaged = plain.toJson().replaceFirst("{", "{\"utcOffsetSeconds\":\"x\",")
        assertNull(TranscriptSessionRecord.fromJson(damaged).utcOffsetSeconds)
        for (absurd in listOf("86400", "-70000")) {
            assertNull(TranscriptSessionRecord.fromJson(plain.toJson().replaceFirst("{", "{\"utcOffsetSeconds\":$absurd,")).utcOffsetSeconds)
        }
        assertEquals(telAviv, TranscriptSessionRecord.fromJson(kept.toJson()).utcOffsetSeconds)
    }

    @Test
    fun aLaterSaveFromAnotherZoneKeepsTheOffsetOfTheFirst() {
        val store = store()
        val first = record(telAviv)
        store.save(first)
        store.save(first.copy(utcOffsetSeconds = london, title = "Visit"))
        assertEquals(telAviv, store.load(first.id)?.utcOffsetSeconds)
    }

    @Test
    fun starredLinesAreSharedInTheConversationsOwnZone() {
        val line = StarredLine(UUID.randomUUID(), halfPastMidnightInTelAviv, record(null).segments[0], utcOffsetSeconds = telAviv)
        assertTrue(TranscriptHistoryStore.exportStarredText(listOf(line), utcOffsetAt = { london }).contains("[00:30:00] hello"))
    }
}
