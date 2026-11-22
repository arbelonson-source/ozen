package com.arbelonson.ozen.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertFalse(failing.saveNow(record(UUID.randomUUID(), 1, false)))
        blocked.delete()
        assertTrue(failing.saveNow(record(UUID.randomUUID(), 1, false)))
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
    fun `a word fixed in History also retries the other conversation that failed to save`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-retries"))
        val saved = record(UUID.randomUUID(), 1, true)
        writer.saveNow(saved)
        val refused = record(UUID.randomUUID(), 1, true)
        permissions(dir, "r-x------")
        writer.saveNow(refused)
        permissions(dir, "rwx------")
        assertNull(store.load(refused.id))

        assertTrue(writer.fixTextNow(saved.id, saved.segments[0].id, "fixed"))

        assertNotNull(store.load(refused.id))
    }

    @Test
    fun `a text fix the disk refused on an old conversation is written by the next save, and reported until then`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.text-fix"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw aval", null, null, 100.0, true)))
        writer.saveNow(old)
        permissions(dir, "r-x------")
        writer.fixTextNow(old.id, line, "I saw Avi")
        assertNotNull(writer.lastFailure)

        permissions(dir, "rwx------")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals("I saw Avi", store.load(old.id)?.segments?.first()?.text)
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a text fix waiting for room is kept while its conversation cannot be read, and written once it can`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.unreadable-fix"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw aval", null, null, 100.0, true)))
        writer.saveNow(old)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        permissions(dir, "r-x------")
        writer.fixTextNow(old.id, line, "I saw Avi")
        permissions(dir, "rwx------")
        permissions(file, "---------")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertNotNull(writer.lastFailure)

        permissions(file, "rw-------")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals("I saw Avi", store.load(old.id)?.segments?.first()?.text)
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a newer text fix is not overwritten by an older one still waiting for the same line`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.newer-fix"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw aval and Dan", null, null, 100.0, true)))
        writer.saveNow(old)
        permissions(dir, "r-x------")
        writer.fixTextNow(old.id, line) { it.replace("aval", "Avi") }
        permissions(dir, "rwx------")
        writer.fixTextNow(old.id, line) { it.replace("Dan", "Dana") }
        assertEquals("I saw Avi and Dana", store.load(old.id)?.segments?.first()?.text)
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals("I saw Avi and Dana", store.load(old.id)?.segments?.first()?.text)
    }

    @Test
    fun `a newer fixed text is not replaced by an older one still waiting for the same line`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.newer-text"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw aval", null, null, 100.0, true)))
        writer.saveNow(old)
        permissions(dir, "r-x------")
        writer.fixTextNow(old.id, line, "first")
        permissions(dir, "rwx------")
        writer.fixTextNow(old.id, line, "second")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals("second", store.load(old.id)?.segments?.first()?.text)
    }

    // History's dialog says a fix it was told was not saved can be tried
    // again. One the disk refused was kept waiting anyway, and the tap that
    // tried again fixed the words a second time.
    @Test
    fun `a word fixed in History that waits for room counts as saved and is written once there is room`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-waits-for-room"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw Cohen today", null, null, 100.0, true)))
        writer.saveNow(old)
        val fix = { text: String -> WordFixes.apply(listOf(WordFix("Cohen", "Avi Cohen")), text) }
        permissions(dir, "r-x------")
        val kept = writer.fixTextNow(old.id, line, fix)
        assertNotNull(writer.lastFailure)

        permissions(dir, "rwx------")
        if (kept != WordFixResult.Saved) writer.fixTextNow(old.id, line, fix)
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals("I saw Avi Cohen today", store.load(old.id)?.segments?.first()?.text)
        assertEquals(WordFixResult.Saved, kept, "kept waiting for room, so History calls it saved")
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a word fixed in History while its conversation cannot be read is not saved, and does not turn up once it can`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-unreadable"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw Cohen today", null, null, 100.0, true)))
        writer.saveNow(old)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        permissions(file, "---------")
        assertEquals(WordFixResult.Unreadable, writer.fixTextNow(old.id, line) { WordFixes.apply(listOf(WordFix("Cohen", "Avi Cohen")), it) })

        permissions(file, "rw-------")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals("I saw Cohen today", store.load(old.id)?.segments?.first()?.text)
        assertNull(writer.lastFailure)
    }

    // History said the line changed or was gone, though the line was fine
    // and the same fix went through once the file could be read.
    @Test
    fun `a word fixed in History while its conversation cannot be read says so, and goes through when tried once it can`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-unreadable-try-again"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw Cohen today", null, null, 100.0, true)))
        writer.saveNow(old)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        val fix = { text: String -> WordFixes.apply(listOf(WordFix("Cohen", "Avi Cohen")), text) }
        permissions(file, "---------")
        assertEquals(WordFixResult.Unreadable, writer.fixTextNow(old.id, line, fix))
        assertEquals(WordFixResult.LineChanged, writer.fixTextNow(UUID.randomUUID(), line, fix), "no file at all is a conversation that is gone")

        permissions(file, "rw-------")
        assertEquals(WordFixResult.Saved, writer.fixTextNow(old.id, line, fix))
        assertEquals("I saw Avi Cohen today", store.load(old.id)?.segments?.first()?.text)
        assertNull(writer.lastFailure)
    }

    // The read was kept as the last save's error, so the caption screen said
    // the phone was probably full, though nothing had failed to save. The
    // iPhone keeps a read out of it.
    @Test
    fun `a word fixed in History while its conversation cannot be read just now is not a save that failed`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-unreadable-not-full"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw Cohen today", null, null, 100.0, true)))
        writer.saveNow(old)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        permissions(file, "---------")
        assertEquals(WordFixResult.Unreadable, writer.fixTextNow(old.id, line) { WordFixes.apply(listOf(WordFix("Cohen", "Avi Cohen")), it) })
        assertNull(writer.lastFailure, "nothing failed to save")
        permissions(file, "rw-------")
    }

    // A fix that only read the file put its own outcome in place of a save
    // that had really failed: a read error, or none at all for a line that
    // was gone, and the warning went while the phone was still full.
    @Test
    fun `a word fixed in History that only reads its conversation leaves a save that failed before reported`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-read-keeps-failure"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw Cohen today", null, null, 100.0, true)))
        writer.saveNow(old)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        val other = record(UUID.randomUUID(), 1, true)
        writer.saveNow(other)
        val fix = { text: String -> WordFixes.apply(listOf(WordFix("Cohen", "Avi Cohen")), text) }
        permissions(dir, "r-x------")
        assertFalse(writer.renameNow(other.id, "Dinner"))
        val full = assertNotNull(writer.lastFailure)

        permissions(file, "---------")
        assertEquals(WordFixResult.Unreadable, writer.fixTextNow(old.id, line, fix))
        assertEquals(full, writer.lastFailure, "the phone is still full")
        permissions(file, "rw-------")
        assertEquals(WordFixResult.LineChanged, writer.fixTextNow(other.id, UUID.randomUUID(), fix))
        assertEquals(full, writer.lastFailure, "a line that is gone says nothing about room either")
        permissions(dir, "rwx------")
    }

    // The iPhone fixes the words a fix still waiting for room left. Android
    // worked them out from the file again, so a second fix of the line while
    // the file couldn't be read just now said to try again, though it could
    // be saved like the first.
    @Test
    fun `a word fixed in History on a line whose earlier fix waits for room fixes those words, even while the file cannot be read`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-after-waiting-fix"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw aval and Dan", null, null, 100.0, true)))
        writer.saveNow(old)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        permissions(dir, "r-x------")
        assertEquals(WordFixResult.Saved, writer.fixTextNow(old.id, line) { it.replace("aval", "Avi") })
        permissions(file, "---------")
        assertEquals(WordFixResult.Saved, writer.fixTextNow(old.id, line) { it.replace("Dan", "Dana") })
        assertEquals(WordFixResult.LineChanged, writer.fixTextNow(old.id, line) { it.replace("aval", "Avital") }, "the waiting words no longer have it")

        permissions(dir, "rwx------")
        permissions(file, "rw-------")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals("I saw Avi and Dana", store.load(old.id)?.segments?.first()?.text)
        assertNull(writer.lastFailure)
    }

    // Once there was room, a History fix of a line only the version waiting
    // for room has wrote that version, and the caption screen still said
    // the phone was probably full, until the next save.
    @Test
    fun `a word fixed in History that writes what waited for room takes the saving warning away`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-writes-waiting-save"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(UUID.randomUUID(), "I saw Avi", null, null, 100.0, true)))
        writer.saveNow(old)
        permissions(dir, "r-x------")
        assertFalse(writer.saveNow(old.copy(segments = old.segments + SavedSegment(line, "and Cohen", null, null, 101.0, true))))
        assertNotNull(writer.lastFailure)

        permissions(dir, "rwx------")
        assertEquals(WordFixResult.Saved, writer.fixTextNow(old.id, line) { it.replace("Cohen", "Dana Cohen") })
        assertNull(writer.lastFailure, "what waited is written, so there is room")
        assertEquals(listOf("I saw Avi", "and Dana Cohen"), store.load(old.id)?.segments?.map { it.text })
    }

    // A fix of a word the saved line no longer has wrote the file again
    // unchanged, as the iPhone doesn't: that write going through took away
    // the warning of a write that had really been refused, and refused on a
    // full phone it put up a warning of its own for a fix that changed
    // nothing.
    @Test
    fun `a word fixed in History that the saved line no longer has writes nothing and leaves the saving warning as it was`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-unchanged"))
        val line = UUID.randomUUID()
        val old = record(UUID.randomUUID(), 0, true).copy(segments = listOf(SavedSegment(line, "I saw Avi", null, null, 100.0, true)))
        writer.saveNow(old)
        val other = record(UUID.randomUUID(), 1, true)
        writer.saveNow(other)
        val fix = { text: String -> text.replace("aval", "Avi") }
        permissions(dir, "r-x------")
        assertFalse(writer.renameNow(other.id, "Dinner"))
        val full = assertNotNull(writer.lastFailure)

        assertEquals(WordFixResult.LineChanged, writer.fixTextNow(old.id, line, fix))
        assertEquals(full, writer.lastFailure, "nothing was written, so nothing new was refused")
        permissions(dir, "rwx------")
        assertEquals(WordFixResult.LineChanged, writer.fixTextNow(old.id, line, fix))
        assertEquals(full, writer.lastFailure, "nothing was written, so it says nothing about room")
        assertEquals("I saw Avi", store.load(old.id)?.segments?.single()?.text)
    }

    // Read as no conversation, the conversation open in History turned into
    // "Conversation not found" after a star or a name it refused there, and
    // there was nothing left to try again from.
    @Test
    fun `History reads a conversation whose file cannot be read just now as unreadable, not as gone, and still shows its version waiting for room`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.load-unreadable"))
        val early = record(UUID.randomUUID(), 1, false)
        writer.saveNow(early)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        permissions(file, "---------")
        assertFailsWith<IOException> { writer.loadNow(early.id) }
        assertNull(writer.loadNow(UUID.randomUUID()), "no file at all is a conversation that is gone")

        val later = early.copy(segments = early.segments + record(early.id, 1, true).segments, endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        assertNotNull(writer.lastFailure)
        assertEquals(later.segments, writer.loadNow(early.id)?.segments, "the version waiting for room is shown as before")

        permissions(dir, "rwx------")
        permissions(file, "rw-------")
        writer.saveNow(record(UUID.randomUUID(), 1, false))
        assertEquals(later.segments, writer.loadNow(early.id)?.segments)
        writer.deleteNow(early.id)
        assertNull(writer.loadNow(early.id), "a deleted conversation is gone")
    }

    // History's dialog says a fix that waits for room is done. Read from the
    // file, History kept showing the old words, and the person fixed the
    // same word again.
    @Test
    fun `History shows a word fixed while the phone is full with the fix, not the older file`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.load-waiting-text"))
        val line = UUID.randomUUID()
        val evening = record(UUID.randomUUID(), 1, true).let { it.copy(segments = listOf(SavedSegment(line, "I saw Cohen today", null, null, 100.0, true)) + it.segments) }
        writer.saveNow(evening)
        permissions(dir, "r-x------")
        assertEquals(WordFixResult.Saved, writer.fixTextNow(evening.id, line) { WordFixes.apply(listOf(WordFix("Cohen", "Avi Cohen")), it) })
        assertNotNull(writer.lastFailure)

        assertEquals(listOf("I saw Avi Cohen today", "שורה 0"), writer.loadNow(evening.id)?.segments?.map { it.text })
        assertNull(writer.loadNow(UUID.randomUUID()))
        permissions(dir, "rwx------")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals(listOf("I saw Avi Cohen today", "שורה 0"), store.load(evening.id)?.segments?.map { it.text })
        assertEquals(store.load(evening.id), writer.loadNow(evening.id))
    }

    @Test
    fun `History shows the newer version of a conversation still waiting for room, with a word fixed in it`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.load-waiting-conversation"))
        val early = record(UUID.randomUUID(), 1, false)
        writer.saveNow(early)
        val later = early.copy(segments = listOf(early.segments[0].copy(text = "I saw aval today")) + record(early.id, 1, true).segments, endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        assertEquals(WordFixResult.Saved, writer.fixTextNow(early.id, early.segments[0].id) { it.replace("aval", "Avi") })

        val shown = writer.loadNow(early.id)
        assertEquals(200.0, shown?.endedAt)
        assertEquals(listOf("I saw Avi today", "שורה 0"), shown?.segments?.map { it.text })
    }

    // Room came back before the refused fix: like every other fix, it
    // writes what was waiting, so the newer version isn't left only in
    // memory until the next save.
    @Test
    fun `a refused History fix still writes the conversation waiting for room once there is room`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-fix-catches-up"))
        val gone = UUID.randomUUID()
        val early = record(UUID.randomUUID(), 1, false).let { it.copy(segments = it.segments + SavedSegment(gone, "I saw aval today", null, null, 100.0, true)) }
        writer.saveNow(early)
        val later = early.copy(segments = listOf(early.segments[0], SavedSegment(UUID.randomUUID(), "I saw Avi today", null, null, 100.0, true)), endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        permissions(dir, "rwx------")

        assertEquals(WordFixResult.LineChanged, writer.fixTextNow(early.id, gone) { it.replace("aval", "Avital") })
        assertEquals(listOf("שורה 0", "I saw Avi today"), store.load(early.id)?.segments?.map { it.text })
        assertNull(writer.lastFailure, "nothing is left waiting, so the warning goes")
    }

    // A replay took the line off the screen before the conversation closed,
    // so the version waiting for room no longer has it. The fix was written
    // to the file and called saved, then the waiting version was written
    // over it without the line.
    @Test
    fun `a word fixed in History on a line the conversation's version waiting for room no longer has is not saved`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-line-not-waiting"))
        val gone = UUID.randomUUID()
        val early = record(UUID.randomUUID(), 1, false).let { it.copy(segments = it.segments + SavedSegment(gone, "I saw aval today", null, null, 100.0, true)) }
        writer.saveNow(early)
        val later = early.copy(segments = listOf(early.segments[0], SavedSegment(UUID.randomUUID(), "I saw Avi today", null, null, 100.0, true)), endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        permissions(dir, "rwx------")

        assertEquals(WordFixResult.LineChanged, writer.fixTextNow(early.id, gone) { it.replace("aval", "Avital") })
        assertEquals(false, writer.loadNow(early.id)?.segments?.any { it.id == gone }, "History doesn't show the line")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals(listOf("שורה 0", "I saw Avi today"), store.load(early.id)?.segments?.map { it.text })
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a word fixed in History on a line whose conversation was saved again after its text queued for room fixes the newer words`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.fix-newer-save"))
        val line = UUID.randomUUID()
        val evening = record(UUID.randomUUID(), 0, false).copy(segments = listOf(SavedSegment(line, "I saw aval today", null, null, 100.0, true)))
        writer.saveNow(evening)
        permissions(dir, "r-x------")
        writer.fixTextNow(evening.id, line, "I saw Dan today")
        writer.saveNow(evening.copy(segments = listOf(evening.segments[0].copy(text = "I saw Dan at the market")), endedAt = 200.0))

        assertEquals(WordFixResult.Saved, writer.fixTextNow(evening.id, line) { it.replace("Dan", "Dana") })
        permissions(dir, "rwx------")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals("I saw Dana at the market", store.load(evening.id)?.segments?.first()?.text)
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a line's text waiting for room does not replace the newer words of a later save of its conversation`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.waiting-text-newer-save"))
        val line = UUID.randomUUID()
        val evening = record(UUID.randomUUID(), 0, false).copy(segments = listOf(SavedSegment(line, "I saw aval today", null, null, 100.0, true)))
        writer.saveNow(evening)
        val later = evening.copy(segments = listOf(evening.segments[0].copy(text = "I saw Avi at the market")), endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.fixTextNow(evening.id, line, "I saw Avi today")
        writer.saveNow(later)

        permissions(dir, "rwx------")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        assertEquals("I saw Avi at the market", store.load(evening.id)?.segments?.first()?.text)
        permissions(dir, "r-x------")
        writer.fixTextNow(evening.id, line, "I saw Dan today")
        permissions(dir, "rwx------")
        writer.saveInBackground(later)
        writer.waitUntilIdle()
        assertEquals("I saw Avi at the market", store.load(evening.id)?.segments?.first()?.text, "an autosave that reached the disk is newer too")
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a star tapped in History follows the conversation as History shows it, the version waiting for room, and the file takes the same state`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-unstar"))
        val early = record(UUID.randomUUID(), 2, false)
        writer.saveNow(early)
        val starredFirst = early.segments.toMutableList().also { it[0] = it[0].copy(isStarred = true) }
        val later = early.copy(segments = starredFirst, endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        permissions(dir, "rwx------")

        assertEquals(true, writer.loadNow(early.id)?.segments?.get(0)?.isStarred)
        assertEquals(false, writer.toggleStarNow(early.id, early.segments[0].id))
        assertEquals(false, store.load(early.id)?.segments?.get(0)?.isStarred)
        assertEquals(200.0, store.load(early.id)?.endedAt)
        assertNull(writer.lastFailure)
    }

    // The starred lines list shows the files' stars. Its Remove the star,
    // as a tap following the waiting version (no star), put the star back.
    @Test
    fun `Remove the star from the starred lines list takes the star off, though the version waiting for room has none`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.unstar-from-list"))
        val plain = record(UUID.randomUUID(), 2, false)
        val early = plain.copy(segments = plain.segments.toMutableList().also { it[0] = it[0].copy(isStarred = true) })
        writer.saveNow(early)
        val later = plain.copy(endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        permissions(dir, "rwx------")

        assertEquals(false, writer.toggleStarNow(early.id, early.segments[0].id, keepingRefused = false, to = false))
        assertEquals(false, store.load(early.id)?.segments?.get(0)?.isStarred)
        assertEquals(200.0, store.load(early.id)?.endedAt)
        assertEquals(false, writer.loadNow(early.id)?.segments?.get(0)?.isStarred)
        assertNull(writer.lastFailure)
    }

    @Test
    fun `a star History cannot save stays out of the waiting version, while one from the caption screen waits with it`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-star-waiting"))
        val early = record(UUID.randomUUID(), 2, false)
        writer.saveNow(early)
        val later = early.copy(endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)

        assertNull(writer.toggleStarNow(early.id, early.segments[0].id, keepingRefused = false))
        assertEquals(true, writer.toggleStarNow(early.id, early.segments[1].id))
        permissions(dir, "rwx------")
        writer.saveNow(record(UUID.randomUUID(), 1, false))
        assertEquals(200.0, store.load(early.id)?.endedAt)
        assertEquals(listOf(false, true), store.load(early.id)?.segments?.map { it.isStarred }, "the caption screen's star waits with the version waiting for room")
    }

    @Test
    fun `a name the disk refuses says so, unless a version waiting for room keeps it`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.refused-name"))
        val morning = record(UUID.randomUUID(), 2, true)
        val evening = record(UUID.randomUUID(), 2, false)
        writer.saveNow(morning)
        writer.saveNow(evening)
        permissions(dir, "r-x------")
        writer.saveNow(evening.copy(endedAt = 200.0))

        assertFalse(writer.renameNow(morning.id, "בוקר"))
        assertTrue(writer.renameNow(evening.id, "ערב"))
        permissions(dir, "rwx------")
        assertTrue(writer.renameNow(UUID.randomUUID(), "לילה"))
        assertNull(store.load(morning.id)?.title)
        assertEquals("ערב", store.load(evening.id)?.title)
    }

    // Taken for gone, a file that couldn't be read just now was named with
    // nothing written, and History said the name was saved. Its outcome,
    // none at all, also took the place of a save that had really failed,
    // and the warning went while the phone was still full.
    @Test
    fun `a name given in History while its conversation cannot be read just now is not saved, and is not a save that failed`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.rename-unreadable"))
        val evening = record(UUID.randomUUID(), 1, true)
        val other = record(UUID.randomUUID(), 1, true)
        writer.saveNow(evening)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        writer.saveNow(other)
        permissions(file, "---------")
        assertFalse(writer.renameNow(evening.id, "Dinner"), "History says the name wasn't saved")
        assertNull(writer.lastFailure, "nothing failed to save")

        permissions(dir, "r-x------")
        assertFalse(writer.renameNow(other.id, "Dinner"))
        val full = assertNotNull(writer.lastFailure, "a name the disk refuses is still reported")
        assertFalse(writer.renameNow(evening.id, "Dinner"))
        assertEquals(full, writer.lastFailure, "the phone is still full")

        assertFalse(writer.saveNow(record(UUID.randomUUID(), 1, true)))
        permissions(dir, "rwx------")
        assertFalse(writer.renameNow(evening.id, "Dinner"))
        assertNull(writer.lastFailure, "what waited is written, so there is room")

        permissions(file, "rw-------")
        assertTrue(writer.renameNow(evening.id, "Dinner"))
        assertEquals("Dinner", store.load(evening.id)?.title)
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

    // The version waiting for room is the one the caption screen saved,
    // which knows nothing of a name given in History or of what first wrote
    // the conversation; its save keeps those from the file.
    @Test
    fun `History shows a version waiting for room with the name and source its save keeps from the file`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.load-waiting-details"))
        val early = record(UUID.randomUUID(), 1, false).copy(inputName = "Kitchen", languageCode = "he")
        writer.saveNow(early)
        assertTrue(writer.renameNow(early.id, "Doctor"))
        val later = early.copy(segments = early.segments + record(early.id, 1, true).segments, title = null, inputName = "Car", languageCode = "en", endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)

        val shown = writer.loadNow(early.id)!!
        assertEquals(listOf("Doctor", "Kitchen", "he"), listOf(shown.title, shown.inputName, shown.languageCode))
        assertEquals(2, shown.segments.size)
        permissions(dir, "rwx------")
        writer.saveNow(record(UUID.randomUUID(), 1, true))
        val saved = store.load(early.id)!!
        assertEquals(listOf(shown.title, shown.inputName, shown.languageCode), listOf(saved.title, saved.inputName, saved.languageCode))
        assertEquals(2, saved.segments.size)
    }

    // History is told a star it can't save didn't change. A line only the
    // waiting version has isn't in the file, so the file had nothing to
    // refuse, and the star was called saved while it only waited.
    @Test
    fun `a star from History on a line only the waiting version has counts once that version reaches the disk`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.history-star-line-only-waiting"))
        val early = record(UUID.randomUUID(), 1, false)
        writer.saveNow(early)
        val later = early.copy(segments = early.segments + record(early.id, 1, true).segments, endedAt = 200.0)
        permissions(dir, "r-x------")
        writer.saveNow(later)
        val added = later.segments[1].id

        assertNull(writer.toggleStarNow(early.id, added, keepingRefused = false))
        assertEquals(listOf(false, false), writer.loadNow(early.id)?.segments?.map { it.isStarred })
        permissions(dir, "rwx------")
        assertEquals(true, writer.toggleStarNow(early.id, added, keepingRefused = false))
        assertEquals(listOf(false, true), store.load(early.id)?.segments?.map { it.isStarred })
        assertNull(writer.lastFailure)
    }

    // A star that wrote nothing put its outcome, none at all, in place of a
    // save that had really failed, and the warning went while the phone was
    // still full. A word fix that writes nothing leaves it.
    @Test
    fun `a star from History that writes nothing leaves a save that failed before reported, and goes once what waited is written`() = withWriterDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store, SerialQueue("test.history-star-writes-nothing"))
        val evening = record(UUID.randomUUID(), 1, true)
        val other = record(UUID.randomUUID(), 1, true)
        writer.saveNow(evening)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        writer.saveNow(other)
        val line = evening.segments[0].id
        permissions(dir, "r-x------")
        assertFalse(writer.renameNow(other.id, "Dinner"))
        val full = assertNotNull(writer.lastFailure)

        permissions(file, "---------")
        assertNull(writer.toggleStarNow(evening.id, line, keepingRefused = false))
        assertEquals(full, writer.lastFailure, "the phone is still full")
        permissions(file, "rw-------")
        assertNull(writer.toggleStarNow(other.id, UUID.randomUUID(), keepingRefused = false))
        assertEquals(full, writer.lastFailure, "a line that is gone says nothing about room either")

        permissions(dir, "rwx------")
        assertEquals(true, writer.toggleStarNow(evening.id, line, keepingRefused = false))
        assertNull(writer.lastFailure)
        permissions(dir, "r-x------")
        assertNull(writer.toggleStarNow(evening.id, line, keepingRefused = false))
        assertNotNull(writer.lastFailure, "a star the disk refuses is still reported")

        assertFalse(writer.saveNow(record(UUID.randomUUID(), 1, true)))
        permissions(dir, "rwx------")
        permissions(file, "---------")
        assertNull(writer.toggleStarNow(evening.id, line, keepingRefused = false))
        assertNull(writer.lastFailure, "what waited is written, so there is room")
        permissions(file, "rw-------")
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
