package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentConversationTest {
    private val now = 2_000_000_000.0

    private fun summary(
        id: UUID = UUID.randomUUID(),
        startedMinutesAgo: Double,
        endedMinutesAgo: Double? = null,
        lastLineMinutesAgo: Double? = null,
        lines: Int = 3,
    ) = TranscriptSessionSummary(
        id = id,
        startedAt = now - startedMinutesAgo * 60,
        endedAt = endedMinutesAgo?.let { now - it * 60 },
        segmentCount = lines,
        preview = "שלום",
        engine = TranscriptionEngineKind.WhisperKit,
        lastLineAt = lastLineMinutesAgo?.let { now - it * 60 },
    )

    private fun record(startedAt: Double, segments: List<SavedSegment>) = TranscriptSessionRecord(
        id = UUID.randomUUID(), startedAt = startedAt, endedAt = null, engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = null, inputName = null, segments = segments,
    )

    private fun saved(text: String, at: Double) = SavedSegment(
        id = UUID.randomUUID(), text = text, speakerName = null, speakerClusterID = null, startTimestamp = at, isCommitted = true,
    )

    private fun temporaryDirectory(prefix: String) = File(System.getProperty("java.io.tmpdir"), "$prefix-${UUID.randomUUID()}")

    @Test
    fun `a conversation cut off a few minutes ago is offered, judged by its last line`() {
        val cutOff = summary(startedMinutesAgo = 45.0, lastLineMinutesAgo = 3.0)
        assertEquals(cutOff.id, RecentConversation.resumable(listOf(cutOff), now)?.id)
        assertEquals(3, RecentConversation.minutesAgo(cutOff, now))
    }

    @Test
    fun `the newest one wins when several are recent`() {
        val earlier = summary(startedMinutesAgo = 30.0, endedMinutesAgo = 15.0)
        val later = summary(startedMinutesAgo = 14.0, lastLineMinutesAgo = 2.0)
        assertEquals(later.id, RecentConversation.resumable(listOf(later, earlier), now)?.id)
        assertEquals(later.id, RecentConversation.resumable(listOf(earlier, later), now)?.id)
    }

    @Test
    fun `a conversation of a single line is offered`() {
        val oneLine = summary(startedMinutesAgo = 2.0, lastLineMinutesAgo = 1.0, lines = 1)
        assertEquals(oneLine.id, RecentConversation.resumable(listOf(oneLine), now)?.id)
    }

    @Test
    fun `nothing is offered for an old conversation, an empty one, or the one already under way`() {
        val old = summary(startedMinutesAgo = 90.0, endedMinutesAgo = 60.0)
        val empty = summary(startedMinutesAgo = 2.0, lastLineMinutesAgo = 1.0, lines = 0)
        val current = summary(startedMinutesAgo = 2.0, lastLineMinutesAgo = 1.0)
        assertNull(RecentConversation.resumable(listOf(old, empty), now))
        assertNull(RecentConversation.resumable(listOf(current), now, excluding = current.id))
        val atBreak = summary(startedMinutesAgo = 40.0, endedMinutesAgo = ConversationBreak.QUIET_SECONDS / 60)
        assertNull(RecentConversation.resumable(listOf(atBreak), now))
    }

    @Test
    fun `a conversation dated well into the future (a wrong clock) is not offered, a slightly early clock is fine`() {
        val farFuture = summary(startedMinutesAgo = -60.0, lastLineMinutesAgo = -50.0)
        val slightlyAhead = summary(startedMinutesAgo = 5.0, lastLineMinutesAgo = -1.0)
        assertNull(RecentConversation.resumable(listOf(farFuture), now))
        assertEquals(slightlyAhead.id, RecentConversation.resumable(listOf(slightlyAhead), now)?.id)
        assertEquals(1, RecentConversation.minutesAgo(slightlyAhead, now))
    }

    @Test
    fun `a saved summary carries when its newest line began, and old cached summaries are rebuilt to include it`() {
        val dir = temporaryDirectory("ozen-recent")
        try {
            val store = TranscriptHistoryStore(dir)
            val record = record(100.0, listOf(saved("א", 100.0), saved("ב", 700.0)))
            store.save(record)
            assertEquals(700.0, store.listSummaries().first().lastLineAt)
            assertEquals(700.0, store.listSummaries().first().lastActiveAt)

            val id = record.id.toString().uppercase()
            val summaryFile = File(File(dir, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME), "$id.json")
            val previous = """{"format":3,"summary":{"id":"$id","startedAt":100,"segmentCount":2,"preview":"א","engine":"whisperKit","speakerNames":[],"starredCount":0}}"""
            summaryFile.writeText(previous)
            assertEquals(700.0, store.listSummaries().first().lastLineAt)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `only conversations saved recently are opened when looking for one cut off`() {
        val dir = temporaryDirectory("ozen-recent-files")
        try {
            val store = TranscriptHistoryStore(dir)
            val old = record(100.0, listOf(saved("שלום", 100.0)))
            val fresh = record(100.0, listOf(saved("שלום", 100.0)))
            store.save(old)
            store.save(fresh)
            val lastWeek = System.currentTimeMillis() - 7 * 86_400_000L
            Files.setLastModifiedTime(File(dir, "${old.id.toString().uppercase()}.json").toPath(), FileTime.fromMillis(lastWeek))

            val current = System.currentTimeMillis() / 1_000.0
            val found = store.summariesModifiedSince(RecentConversation.oldestQualifyingSave(current))
            assertEquals(listOf(fresh.id), found.map { it.id })
            assertEquals(2, store.listSummaries().size)
            assertTrue(RecentConversation.oldestQualifyingSave(current) < current - ConversationBreak.QUIET_SECONDS)
        } finally {
            dir.deleteRecursively()
        }
    }
}
