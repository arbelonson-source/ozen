package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranscriptAutosaveMemoryTest {
    private fun lines(count: Int) = (0 until count).map {
        SavedSegment(UUID.nameUUIDFromBytes("line$it".toByteArray()), "word$it", null, null, it.toDouble(), true)
    }
    private fun record(id: UUID, count: Int, engine: TranscriptionEngineKind = TranscriptionEngineKind.WhisperKit, title: String? = null, ended: Boolean = false) =
        TranscriptSessionRecord(id = id, startedAt = 100.0, endedAt = if (ended) 900.0 else null, title = title, engine = engine, modelVariant = "small", inputName = "mic", segments = lines(count))
    private fun withDirectory(body: (File) -> Unit) {
        val dir = File(System.getProperty("java.io.tmpdir"), "ozen-autosave-${UUID.randomUUID()}")
        try { body(dir) } finally { dir.deleteRecursively() }
    }
    @Test
    fun `later saves of a growing record do not read the conversation file`() = withDirectory { dir ->
        val id = UUID.randomUUID()
        val store = TranscriptHistoryStore(dir)
        store.save(record(id, 1))
        val fresh = TranscriptHistoryStore(dir)
        fresh.save(record(id, 2))
        assertEquals(1, fresh.conversationReads.get(), "a new store reads the existing file once")
        val before = fresh.conversationReads.get()
        fresh.save(record(id, 3))
        fresh.save(record(id, 4))
        assertEquals(0, fresh.conversationReads.get() - before, "reads during the second and third saves")
    }
    @Test
    fun `the first engine wins across remembered saves and a rename is kept`() = withDirectory { dir ->
        val id = UUID.randomUUID()
        val store = TranscriptHistoryStore(dir)
        store.save(record(id, 1))
        store.save(record(id, 2, engine = TranscriptionEngineKind.HomeServer))
        store.rename(id, "Lecture")
        store.save(record(id, 3, engine = TranscriptionEngineKind.Cloud))
        val loaded = assertNotNull(store.load(id))
        assertEquals(TranscriptionEngineKind.WhisperKit, loaded.engine)
        assertEquals("Lecture", loaded.title)
    }
    @Test
    fun `a file changed by another store is read again and its title and source kept`() = withDirectory { dir ->
        val id = UUID.randomUUID()
        val store = TranscriptHistoryStore(dir)
        store.save(record(id, 1))
        store.save(record(id, 2))
        File(dir, "${id.toString().uppercase()}.json").delete()
        TranscriptHistoryStore(dir).save(record(id, 5, engine = TranscriptionEngineKind.Cloud, title = "Renamed elsewhere"))
        val before = store.conversationReads.get()
        store.save(record(id, 6))
        assertTrue(store.conversationReads.get() > before, "the changed file must be read again")
        val loaded = assertNotNull(store.load(id))
        assertEquals("Renamed elsewhere", loaded.title)
        assertEquals(TranscriptionEngineKind.Cloud, loaded.engine)
        File(dir, "${id.toString().uppercase()}.json").delete()
        store.save(record(id, 7, engine = TranscriptionEngineKind.HomeServer))
        assertEquals(TranscriptionEngineKind.HomeServer, store.load(id)?.engine)
    }
    @Test
    fun `six autosaves inside two minutes refresh the caches once and history still sees the last one`() = withDirectory { dir ->
        val id = UUID.randomUUID()
        var now = 1_000_000L
        val store = TranscriptHistoryStore(dir, nowMillis = { now })
        for (n in 1..6) {
            store.save(record(id, n), throttleCaches = true)
            now += 20_000
        }
        assertEquals(1, store.cacheRefreshes.get(), "cache refreshes in six autosaves")
        assertEquals(listOf(id), store.search("word5").map { it.id })
        assertEquals(6, store.listSummaries().single().segmentCount)
        now += 120_000
        val ended = TranscriptHistoryStore(dir, nowMillis = { now })
        ended.save(record(id, 7, ended = true))
        assertEquals(1, ended.cacheRefreshes.get())
        assertEquals(7, ended.listSummaries().single().segmentCount)
    }
    @Test
    fun `the writer's autosave throttles caches but an ended record refreshes them`() = withDirectory { dir ->
        val id = UUID.randomUUID()
        val store = TranscriptHistoryStore(dir)
        val writer = TranscriptHistoryWriter(store)
        fun save(record: TranscriptSessionRecord) {
            val done = CountDownLatch(1)
            writer.saveInBackground(record) { done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS))
        }
        save(record(id, 1))
        save(record(id, 2))
        save(record(id, 3))
        assertEquals(1, store.cacheRefreshes.get())
        save(record(id, 4, ended = true))
        assertEquals(2, store.cacheRefreshes.get())
    }
}
