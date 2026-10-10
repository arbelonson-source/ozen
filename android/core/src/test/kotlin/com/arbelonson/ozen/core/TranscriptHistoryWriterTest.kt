package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun permissions(file: File, text: String) {
    Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(text))
}

private fun withWriterDirectory(body: (File) -> Unit) {
    val directory = File(System.getProperty("java.io.tmpdir"), "ozen-writer-${UUID.randomUUID()}")
    try {
        body(directory)
    } finally {
        if (directory.exists() && directory.isDirectory) runCatching { permissions(directory, "rwx------") }
        directory.deleteRecursively()
    }
}

private fun waitFor(semaphore: Semaphore, millis: Long): Boolean = semaphore.tryAcquire(millis, TimeUnit.MILLISECONDS)

class TranscriptHistoryWriterTest {
    private fun record(id: UUID, lines: Int, ended: Boolean) = TranscriptSessionRecord(
        id = id,
        startedAt = 100.0,
        endedAt = if (ended) 200.0 else null,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = null,
        inputName = null,
        segments = (0 until lines).map {
            SavedSegment(UUID.randomUUID(), "שורה $it", null, null, 100.0, true)
        },
    )

    private fun offThread(work: () -> Unit): Semaphore {
        val done = Semaphore(0)
        Thread {
            work()
            done.release()
        }.start()
        return done
    }

    private fun summaryFile(dir: File, id: UUID) =
        File(File(dir, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME), "${id.toString().uppercase()}.json")

    private fun removeRecordFiles(dir: File) {
        for (file in dir.listFiles().orEmpty()) if (file.name.endsWith(".json")) file.delete()
    }

    @Test
    fun `an autosave returns without waiting for the disk`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val queue = SerialQueue("test.writer")
        val writer = TranscriptHistoryWriter(store, queue)

        queue.suspend()
        val returned = offThread { writer.saveInBackground(record(UUID.randomUUID(), 1, false)) }
        val returnedWhilePaused = waitFor(returned, 1_000)
        assertTrue(returnedWhilePaused)
        assertTrue(store.listSummaries().isEmpty())

        queue.resume()
        if (!returnedWhilePaused) returned.acquire()
        writer.waitUntilIdle()
        assertEquals(1, store.listSummaries().size)
    }

    @Test
    fun `an autosave says when it is done, by which time a failure is already known`() {
        val blocker = File(System.getProperty("java.io.tmpdir"), "ozen-writer-blocked-${UUID.randomUUID()}")
        blocker.writeBytes("x".toByteArray())
        try {
            val writer = TranscriptHistoryWriter(TranscriptHistoryStore(File(blocker, "history")))
            val done = Semaphore(0)
            var failureWhenDone: String? = null
            writer.saveInBackground(record(UUID.randomUUID(), 1, false)) {
                failureWhenDone = writer.lastFailure
                done.release()
            }
            done.acquire()
            assertNotNull(failureWhenDone)
        } finally {
            blocker.delete()
        }
    }

    @Test
    fun `a rename waits for autosaves already queued, so the name ends up on the newest copy`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val queue = SerialQueue("test.writer")
        val writer = TranscriptHistoryWriter(store, queue)
        val id = UUID.randomUUID()
        writer.saveNow(record(id, 1, false))

        queue.suspend()
        val autosaved = offThread { writer.saveInBackground(record(id, 4, false)) }
        val autosaveReturned = waitFor(autosaved, 1_000)
        val renamed = offThread { writer.renameNow(id, "ארוחת ערב") }
        val renameReturnedEarly = waitFor(renamed, 200)
        queue.resume()
        if (!autosaveReturned) autosaved.acquire()
        if (!renameReturnedEarly) renamed.acquire()
        writer.waitUntilIdle()

        assertEquals(false, renameReturnedEarly)
        assertEquals("ארוחת ערב", store.load(id)?.title)
        assertEquals(4, store.load(id)?.segments?.size)
    }

    @Test
    fun `a save made now lands after an autosave that was still waiting, never under it`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val queue = SerialQueue("test.writer")
        val writer = TranscriptHistoryWriter(store, queue)
        val id = UUID.randomUUID()

        queue.suspend()
        val autosaved = offThread { writer.saveInBackground(record(id, 1, false)) }
        val autosaveReturned = waitFor(autosaved, 1_000)
        val saved = offThread { writer.saveNow(record(id, 3, true)) }
        // A correct writer is stuck behind the paused autosave here; one
        // that skips the queue has already written and returned.
        val saveReturnedEarly = waitFor(saved, 200)
        queue.resume()
        if (!autosaveReturned) autosaved.acquire()
        if (!saveReturnedEarly) saved.acquire()
        writer.waitUntilIdle()

        val summary = store.listSummaries().firstOrNull()
        assertEquals(3, summary?.segmentCount)
        assertEquals(200.0, summary?.endedAt)
    }

    @Test
    fun `a save made now skips the summary and search-text files an autosave would have written`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store)

        val now = record(UUID.randomUUID(), 1, true)
        writer.saveNow(now)
        assertFalse(summaryFile(dir, now.id).exists())
        // The conversation itself is still there and lists correctly --
        // only the cache files are skipped.
        assertEquals(listOf(now.id), store.listSummaries().map { it.id })

        val autosaved = record(UUID.randomUUID(), 1, false)
        writer.saveInBackground(autosaved)
        writer.waitUntilIdle()
        assertTrue(summaryFile(dir, autosaved.id).exists())
    }

    @Test
    fun `a delete waits for an autosave of the same conversation, so the autosave can't bring it back`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val queue = SerialQueue("test.writer")
        val writer = TranscriptHistoryWriter(store, queue)
        val id = UUID.randomUUID()
        writer.saveNow(record(id, 1, false))

        queue.suspend()
        val autosaved = offThread { writer.saveInBackground(record(id, 2, false)) }
        val autosaveReturned = waitFor(autosaved, 1_000)
        val deleted = offThread { runCatching { writer.deleteNow(id) } }
        val deleteReturnedEarly = waitFor(deleted, 200)
        queue.resume()
        if (!autosaveReturned) autosaved.acquire()
        if (!deleteReturnedEarly) deleted.acquire()
        writer.waitUntilIdle()

        assertEquals(false, deleteReturnedEarly)
        assertNull(store.load(id))
        assertTrue(store.listSummaries().isEmpty())
    }

    @Test
    fun `delete all waits for queued autosaves too`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val queue = SerialQueue("test.writer")
        val writer = TranscriptHistoryWriter(store, queue)
        writer.saveNow(record(UUID.randomUUID(), 1, true))

        queue.suspend()
        val autosaved = offThread { writer.saveInBackground(record(UUID.randomUUID(), 2, false)) }
        val autosaveReturned = waitFor(autosaved, 1_000)
        val deleted = offThread { runCatching { writer.deleteAllNow() } }
        val deleteReturnedEarly = waitFor(deleted, 200)
        queue.resume()
        if (!autosaveReturned) autosaved.acquire()
        if (!deleteReturnedEarly) deleted.acquire()
        writer.waitUntilIdle()

        assertEquals(false, deleteReturnedEarly)
        assertTrue(store.listSummaries().isEmpty())
    }

    @Test
    fun `an autosave with nothing new since the last write leaves the disk alone, a new line is written`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store)
        val id = UUID.randomUUID()
        val quiet = record(id, 2, false)
        writer.saveInBackground(quiet)
        writer.waitUntilIdle()
        assertEquals(quiet, store.load(id))

        // Removed behind the writer's back: only a write brings it back.
        removeRecordFiles(dir)
        writer.saveInBackground(quiet)
        writer.waitUntilIdle()
        assertNull(store.load(id))

        val grown = quiet.copy(
            segments = quiet.segments + SavedSegment(UUID.randomUUID(), "עוד שורה", null, null, 150.0, true),
        )
        writer.saveInBackground(grown)
        writer.waitUntilIdle()
        assertEquals(grown, store.load(id))
    }

    @Test
    fun `after a failed write, a delete or a rename, the same conversation is written again`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store)
        val conversation = record(UUID.randomUUID(), 1, false)

        writer.saveNow(conversation)
        dir.deleteRecursively()
        dir.writeBytes("not a folder".toByteArray())
        writer.saveNow(conversation)
        assertNotNull(writer.lastFailure)
        dir.delete()
        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        assertEquals(conversation, store.load(conversation.id))
        assertNull(writer.lastFailure)

        writer.deleteNow(conversation.id)
        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        assertEquals(conversation, store.load(conversation.id))

        writer.renameNow(conversation.id, "ביקור")
        removeRecordFiles(dir)
        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        assertNotNull(store.load(conversation.id))
    }

    @Test
    fun `a line starred after the conversation ended is saved, counted, and keeps the conversation from being cleared out`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.star"))
        val conversation = record(UUID.randomUUID(), 3, true)
        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        val line = conversation.segments[1].id

        assertEquals(true, writer.toggleStarNow(conversation.id, line))
        assertEquals(listOf(false, true, false), store.load(conversation.id)?.segments?.map { it.isStarred })
        val summary = assertNotNull(store.listSummaries().firstOrNull { it.id == conversation.id })
        assertEquals(1, summary.starredCount)
        assertTrue(summary.isKeptByChoice)

        writer.saveInBackground(conversation)
        writer.waitUntilIdle()
        assertEquals(listOf(false, false, false), store.load(conversation.id)?.segments?.map { it.isStarred })
        assertEquals(true, writer.toggleStarNow(conversation.id, line))
        assertEquals(false, writer.toggleStarNow(conversation.id, line))
        assertEquals(0, assertNotNull(store.listSummaries().firstOrNull { it.id == conversation.id }).starredCount)
        assertNull(writer.toggleStarNow(conversation.id, UUID.randomUUID()))
        assertNull(writer.toggleStarNow(UUID.randomUUID(), line))
    }

    @Test
    fun `a save that can't reach the disk is reported, and the next one that does clears it`() = withWriterDirectory { base ->
        base.mkdirs()
        // A file where the history folder should be: every save fails, the
        // way it would on a phone with no room left.
        val blocked = File(base, "history")
        blocked.writeBytes("not a folder".toByteArray())
        val failing = TranscriptHistoryWriter(TranscriptHistoryStore(blocked), SerialQueue("test.fail"))

        assertNull(failing.lastFailure)
        failing.saveInBackground(record(UUID.randomUUID(), 1, false))
        failing.waitUntilIdle()
        assertNotNull(failing.lastFailure)

        // The folder becomes usable again (room was freed): the next save
        // works and the problem is no longer reported.
        blocked.delete()
        failing.saveNow(record(UUID.randomUUID(), 1, false))
        assertNull(failing.lastFailure)
    }

    private fun speakerRecord(): TranscriptSessionRecord = TranscriptSessionRecord(
        id = UUID.randomUUID(), startedAt = 100.0, endedAt = 200.0, engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = null, inputName = null,
        segments = listOf(SavedSegment(UUID.randomUUID(), "שלום", "Avi", null, 100.0, true)),
    )

    @Test
    fun `a voice rename the disk refused is finished by the next save once there is room, and is reported until then`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.rename"))
        val old = speakerRecord()
        writer.saveNow(old)
        permissions(dir, "r-x------")
        writer.renameSpeakerInBackground("Avi", "Aviv")
        writer.waitUntilIdle()
        assertNotNull(writer.lastFailure)

        permissions(dir, "rwx------")
        writer.saveNow(record(UUID.randomUUID(), 1, false))
        assertEquals("Aviv", store.load(old.id)?.segments?.firstOrNull()?.speakerName)
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a conversation the disk refused is written by the next save once there is room, and is reported until then`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused"))
        val evening = record(UUID.randomUUID(), 3, true)
        dir.mkdirs()
        permissions(dir, "r-x------")
        writer.saveNow(evening)
        assertNotNull(writer.lastFailure)
        writer.saveInBackground(record(UUID.randomUUID(), 1, false))
        writer.waitUntilIdle()
        assertNotNull(writer.lastFailure)

        permissions(dir, "rwx------")
        writer.saveNow(record(UUID.randomUUID(), 1, false))
        assertEquals(3, store.load(evening.id)?.segments?.size)
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a refused conversation waiting for room takes a voice rename made meanwhile`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-rename"))
        val evening = speakerRecord()
        dir.mkdirs()
        permissions(dir, "r-x------")
        writer.saveNow(evening)
        permissions(dir, "rwx------")
        writer.renameSpeakerInBackground("Avi", "Aviv")
        writer.saveNow(record(UUID.randomUUID(), 1, false))
        assertEquals("Aviv", store.load(evening.id)?.segments?.firstOrNull()?.speakerName)
    }

    @Test
    fun `a refused conversation deleted before there is room is not written back later`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-deleted"))
        val evening = record(UUID.randomUUID(), 3, true)
        dir.mkdirs()
        permissions(dir, "r-x------")
        writer.saveNow(evening)
        runCatching { writer.deleteNow(evening.id) }

        permissions(dir, "rwx------")
        writer.saveNow(record(UUID.randomUUID(), 1, false))
        assertNull(store.load(evening.id))
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a star put on a conversation still waiting for room is kept when the waiting version is written`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-star"))
        val early = record(UUID.randomUUID(), 2, false)
        writer.saveNow(early)
        val later = early.copy(segments = early.segments + record(early.id, 2, true).segments, endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        permissions(dir, "rwx------")

        val starred = writer.toggleStarNow(early.id, early.segments[0].id)
        writer.saveNow(record(UUID.randomUUID(), 1, false))

        assertEquals(true, starred)
        val saved = assertNotNull(store.load(early.id))
        assertEquals(4, saved.segments.size)
        assertTrue(saved.segments[0].isStarred)
    }

    @Test
    fun `a star tapped in History follows the conversation as History shows it, the file on disk, and the waiting version takes the same state`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-unstar"))
        val early = record(UUID.randomUUID(), 2, false)
        writer.saveNow(early)
        val starredFirst = early.segments.toMutableList().also { it[0] = it[0].copy(isStarred = true) }
        val later = early.copy(segments = starredFirst, endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        permissions(dir, "rwx------")

        assertEquals(true, writer.toggleStarNow(early.id, early.segments[0].id))
        assertEquals(true, store.load(early.id)?.segments?.get(0)?.isStarred)
        assertEquals(200.0, store.load(early.id)?.endedAt)
        assertNull(writer.lastFailure)
    }

    @Test
    fun `deleting everything also drops a conversation still waiting for room, and its warning`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.delete-all-waiting"))
        val evening = record(UUID.randomUUID(), 3, true)
        dir.mkdirs()
        permissions(dir, "r-x------")
        writer.saveNow(evening)
        assertNotNull(writer.lastFailure)
        permissions(dir, "rwx------")

        writer.deleteAllNow()
        assertNull(writer.lastFailure)
        writer.saveNow(record(UUID.randomUUID(), 1, false))
        assertNull(store.load(evening.id))
    }

    @Test
    fun `a star on a line only the waiting version has is put on that line`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-new-line-star"))
        val early = record(UUID.randomUUID(), 2, false)
        writer.saveNow(early)
        val later = early.copy(segments = early.segments + record(early.id, 2, true).segments, endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        permissions(dir, "rwx------")

        val starred = writer.toggleStarNow(early.id, later.segments[2].id)
        writer.saveNow(record(UUID.randomUUID(), 1, false))

        assertEquals(true, starred)
        val saved = assertNotNull(store.load(early.id))
        assertEquals(listOf(false, false, true, false), saved.segments.map { it.isStarred })
    }

    @Test
    fun `a name given to a conversation the disk refused before it was ever written is kept when it is written`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-title"))
        val evening = record(UUID.randomUUID(), 2, true)
        dir.mkdirs()
        permissions(dir, "r-x------")
        writer.saveNow(evening)
        permissions(dir, "rwx------")

        writer.renameNow(evening.id, "ארוחת ערב")
        assertEquals("ארוחת ערב", store.load(evening.id)?.title)
        assertNull(writer.lastFailure)
    }

    @Test
    fun `deleting the one conversation the disk refused takes the saving warning away with it`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-deleted"))
        val refused = record(UUID.randomUUID(), 2, true)
        dir.mkdirs()
        permissions(dir, "r-x------")
        writer.saveNow(refused)
        assertNotNull(writer.lastFailure)

        writer.deleteNow(refused.id)
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a rename that works does not clear the warning while a refused conversation still waits`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-warning"))
        dir.mkdirs()
        permissions(dir, "r-x------")
        writer.saveNow(record(UUID.randomUUID(), 3, true))
        assertNotNull(writer.lastFailure)

        writer.renameNow(UUID.randomUUID(), "ארוחת ערב")
        assertNotNull(writer.lastFailure)
        writer.renameSpeakerInBackground("Avi", "Aviv")
        writer.waitUntilIdle()
        assertNotNull(writer.lastFailure)
    }

    @Test
    fun `once a waiting conversation is written, the warning goes even if the one on screen hasn't changed`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-clears"))
        val refused = record(UUID.randomUUID(), 3, true)
        val blocker = File(dir, "${refused.id.toString().uppercase()}.json")
        blocker.mkdirs()
        writer.saveNow(refused)
        val onScreen = record(UUID.randomUUID(), 1, false)
        writer.saveInBackground(onScreen)
        writer.waitUntilIdle()
        assertNotNull(writer.lastFailure)

        blocker.deleteRecursively()
        writer.saveInBackground(onScreen)
        writer.waitUntilIdle()
        assertEquals(3, store.load(refused.id)?.segments?.size)
        assertNull(writer.lastFailure)
    }
}
