package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400.0
private const val NOW = 2_000_000_000.0

private fun <T> withRetentionStore(body: (TranscriptHistoryStore) -> T): T {
    val directory = File(System.getProperty("java.io.tmpdir"), "ozen-retention-${UUID.randomUUID()}")
    try {
        return body(TranscriptHistoryStore(directory))
    } finally {
        val summaries = File(directory, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME)
        if (summaries.exists()) Files.setPosixFilePermissions(summaries.toPath(), PosixFilePermissions.fromString("rwx------"))
        directory.deleteRecursively()
    }
}

private fun conversation(
    startedDaysAgo: Double,
    endedDaysAgo: Double?,
    starred: Boolean = false,
    title: String? = null,
    lastLineDaysAgo: Double? = null,
    id: UUID = UUID.randomUUID(),
): TranscriptSessionRecord = TranscriptSessionRecord(
    id = id,
    startedAt = NOW - startedDaysAgo * DAY,
    endedAt = endedDaysAgo?.let { NOW - it * DAY },
    engine = TranscriptionEngineKind.WhisperKit,
    modelVariant = null,
    inputName = null,
    segments = listOf(
        SavedSegment(UUID.randomUUID(), "שלום", null, null, NOW - startedDaysAgo * DAY, true, isStarred = starred),
    ) + (lastLineDaysAgo?.let {
        listOf(SavedSegment(UUID.randomUUID(), "להתראות", null, null, NOW - it * DAY, true))
    } ?: emptyList()),
    title = title,
)

class HistoryRetentionTest {
    @Test
    fun `forever keeps everything, the others count back whole days`() {
        assertNull(HistoryRetention.Forever.cutoff(NOW))
        assertEquals(NOW - 7 * DAY, HistoryRetention.Week.cutoff(NOW))
        assertEquals(NOW - 30 * DAY, HistoryRetention.Month.cutoff(NOW))
        assertEquals(NOW - 90 * DAY, HistoryRetention.ThreeMonths.cutoff(NOW))
        assertEquals(NOW - 365 * DAY, HistoryRetention.Year.cutoff(NOW))
    }

    @Test
    fun `old conversations go, recent ones, starred ones and named ones stay`() {
        withRetentionStore { store ->
            val old = conversation(startedDaysAgo = 40.0, endedDaysAgo = 40.0)
            val recent = conversation(startedDaysAgo = 3.0, endedDaysAgo = 3.0)
            val oldStarred = conversation(startedDaysAgo = 40.0, endedDaysAgo = 40.0, starred = true)
            val oldNamed = conversation(startedDaysAgo = 40.0, endedDaysAgo = 40.0, title = "יום הולדת")
            val longRunning = conversation(startedDaysAgo = 40.0, endedDaysAgo = 1.0)
            val neverClosed = conversation(startedDaysAgo = 40.0, endedDaysAgo = null)
            val neverClosedButTalkedYesterday = conversation(startedDaysAgo = 40.0, endedDaysAgo = null, lastLineDaysAgo = 1.0)
            for (record in listOf(old, recent, oldStarred, oldNamed, longRunning, neverClosed, neverClosedButTalkedYesterday)) {
                store.save(record)
            }

            val deleted = store.deleteConversations(inactiveBefore = NOW - 30 * DAY)

            assertEquals(2, deleted)
            val left = store.listSummaries().map { it.id }.toSet()
            assertEquals(setOf(recent.id, oldStarred.id, oldNamed.id, longRunning.id, neverClosedButTalkedYesterday.id), left)
            assertNull(store.load(old.id))
            assertEquals(5, store.search("שלום").size)
        }
    }

    @Test
    fun `picking a shorter time counts what it would delete from every saved conversation, and doesn't know while that list isn't loaded`() {
        val summaries = listOf(
            conversation(startedDaysAgo = 40.0, endedDaysAgo = 40.0),
            conversation(startedDaysAgo = 3.0, endedDaysAgo = 3.0),
            conversation(startedDaysAgo = 40.0, endedDaysAgo = 40.0, starred = true),
            conversation(startedDaysAgo = 40.0, endedDaysAgo = 40.0, title = "יום הולדת"),
            conversation(startedDaysAgo = 400.0, endedDaysAgo = null),
        ).map { TranscriptSessionSummary.summarizing(it) }

        assertEquals(2, HistoryRetention.Month.expiringCount(summaries, NOW))
        assertEquals(1, HistoryRetention.Year.expiringCount(summaries, NOW))
        assertEquals(0, HistoryRetention.Week.expiringCount(emptyList(), NOW))
        assertNull(HistoryRetention.Week.expiringCount(null, NOW))
        assertEquals(0, HistoryRetention.Forever.expiringCount(null, NOW))
    }

    @Test
    fun `the conversation still on screen is never deleted`() {
        withRetentionStore { store ->
            val live = conversation(startedDaysAgo = 40.0, endedDaysAgo = null)
            store.save(live)

            assertEquals(0, store.deleteConversations(inactiveBefore = NOW - 30 * DAY, protecting = setOf(live.id)))
            assertNotNull(store.load(live.id))
        }
    }

    @Test
    fun `the writer does nothing when set to keep forever, and prunes behind queued saves otherwise`() {
        withRetentionStore { store ->
            val queue = SerialQueue("test.retention")
            val writer = TranscriptHistoryWriter(store, queue)
            val old = conversation(startedDaysAgo = 400.0, endedDaysAgo = 400.0)
            writer.saveNow(old)

            assertEquals(0, writer.deleteExpiredNow(HistoryRetention.Forever, NOW, emptySet()))
            assertNotNull(store.load(old.id))

            // An autosave of the same old conversation is still waiting to be
            // written; pruning must run after it, not before.
            queue.suspend()
            writer.saveInBackground(old)
            val pruned = Semaphore(0)
            val count = AtomicInteger()
            thread {
                count.set(writer.deleteExpiredNow(HistoryRetention.Year, NOW, emptySet()))
                pruned.release()
            }
            val returnedEarly = pruned.tryAcquire(200, TimeUnit.MILLISECONDS)
            queue.resume()
            if (!returnedEarly) pruned.acquire()
            writer.waitUntilIdle()

            assertFalse(returnedEarly)
            assertEquals(1, count.get())
            assertTrue(store.listSummaries().isEmpty())
        }
    }

    @Test
    fun `settings default to keeping forever, and an unknown choice from a newer build reads as forever`() {
        assertEquals(HistoryRetention.Forever, AppSettings.default.historyRetention)
        val old = AppSettings.fromJson("{}")
        assertEquals(HistoryRetention.Forever, old.historyRetention)
        val unknown = AppSettings.fromJson("""{"historyRetention":"fortnight"}""")
        assertEquals(HistoryRetention.Forever, unknown.historyRetention)

        val settings = AppSettings.default
        settings.historyRetention = HistoryRetention.Month
        val roundTripped = AppSettings.fromJson(settings.toJson())
        assertEquals(HistoryRetention.Month, roundTripped.historyRetention)
    }
}
