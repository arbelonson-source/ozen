package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptHistoryEditRaceTest {
    private fun line(text: String) = SavedSegment(
        id = UUID.randomUUID(), text = text, speakerName = null, speakerClusterID = null,
        startTimestamp = 100.0, isCommitted = true,
    )

    private fun record(id: UUID, lines: List<SavedSegment>) = TranscriptSessionRecord(
        id = id, startedAt = 100.0, endedAt = null, engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = "small", inputName = null, segments = lines,
    )

    private fun withFolder(body: (File) -> Unit) {
        val folder = File(System.getProperty("java.io.tmpdir"), "ozen-race-${UUID.randomUUID()}")
        try {
            body(folder)
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun raw(folder: File, id: UUID) =
        Json.parseToJsonElement(File(folder, "${id.toString().uppercase()}.json").readText()).jsonObject

    /** Runs [work] on its own thread, waits a short bounded time for it, returns the thread to join later. */
    private fun startedElsewhere(work: () -> Unit): Thread {
        val done = CountDownLatch(1)
        val worker = thread { try { work() } finally { done.countDown() } }
        done.await(300, TimeUnit.MILLISECONDS)
        return worker
    }

    @Test
    fun `a rename does not lose a line the autosave adds meanwhile`() = withFolder { folder ->
        val id = UUID.randomUUID()
        val editing = TranscriptHistoryStore(folder)
        val autosaving = TranscriptHistoryStore(folder)
        val first = listOf(line("one"), line("two"))
        editing.save(record(id, first))
        val longer = record(id, first + line("three"))
        var pending: Thread? = null
        editing.betweenPatchReadAndWrite = { pending = startedElsewhere { autosaving.save(longer) } }
        editing.rename(id, "Visit")
        pending?.join(5_000)
        val saved = raw(folder, id)
        assertEquals(3, saved["segments"]!!.jsonArray.size)
        assertEquals("Visit", saved["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a rename to a same-length title with the file date and size unchanged is not written back over`() = withFolder { folder ->
        val id = UUID.randomUUID()
        val autosaving = TranscriptHistoryStore(folder)
        val editing = TranscriptHistoryStore(folder)
        val saved = record(id, listOf(line("one"), line("two")))
        autosaving.save(saved)
        editing.rename(id, "Alpha")
        autosaving.save(saved)
        val file = File(folder, "${id.toString().uppercase()}.json")
        val seenModified = file.lastModified()
        val seenSize = file.length()
        editing.rename(id, "Bravo")
        assertTrue(file.setLastModified(seenModified))
        assertEquals(seenSize, file.length())
        assertEquals(seenModified, file.lastModified())
        autosaving.save(saved)
        assertEquals("Bravo", raw(folder, id)["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a star does not lose a line the autosave adds meanwhile`() = withFolder { folder ->
        val id = UUID.randomUUID()
        val editing = TranscriptHistoryStore(folder)
        val autosaving = TranscriptHistoryStore(folder)
        val first = listOf(line("one"), line("two"))
        editing.save(record(id, first))
        val longer = record(id, first + line("three"))
        var pending: Thread? = null
        editing.betweenPatchReadAndWrite = { pending = startedElsewhere { autosaving.save(longer) } }
        assertEquals(true, editing.toggleStar(first[0].id, id))
        pending?.join(5_000)
        assertEquals(3, raw(folder, id)["segments"]!!.jsonArray.size)
    }

    @Test
    fun `an edit does not bring back a conversation deleted meanwhile`() = withFolder { folder ->
        val id = UUID.randomUUID()
        val editing = TranscriptHistoryStore(folder)
        val deleting = TranscriptHistoryStore(folder)
        editing.save(record(id, listOf(line("one"), line("two"))))
        var pending: Thread? = null
        editing.betweenPatchReadAndWrite = { pending = startedElsewhere { deleting.delete(id) } }
        editing.rename(id, "Visit")
        pending?.join(5_000)
        assertFalse(File(folder, "${id.toString().uppercase()}.json").exists())
    }

    @Test
    fun `an edit of a deleted conversation changes nothing`() = withFolder { folder ->
        val id = UUID.randomUUID()
        val store = TranscriptHistoryStore(folder)
        val lines = listOf(line("one"), line("two"))
        store.save(record(id, lines))
        store.delete(id)
        store.rename(id, "Visit")
        assertNull(store.toggleStar(lines[0].id, id))
        assertFalse(store.fixText(lines[0].id, id, "x"))
        assertTrue(!File(folder, "${id.toString().uppercase()}.json").exists())
    }
}
