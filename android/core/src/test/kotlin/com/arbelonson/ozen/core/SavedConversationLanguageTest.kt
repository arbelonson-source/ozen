package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

class SavedConversationLanguageTest {
    private fun lines(texts: List<String>) = texts.mapIndexed { i, text ->
        SavedSegment(UUID.nameUUIDFromBytes("line$i".toByteArray()), text, null, null, i.toDouble(), true)
    }
    private fun record(id: UUID = UUID.randomUUID(), language: String?, texts: List<String> = listOf("Take three pills")) =
        TranscriptSessionRecord(id = id, startedAt = 100.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = "small", inputName = "mic", segments = lines(texts), languageCode = language)
    private fun withDirectory(body: (File) -> Unit) {
        val dir = File(System.getProperty("java.io.tmpdir"), "ozen-reclang-${UUID.randomUUID()}")
        try { body(dir) } finally { dir.deleteRecursively() }
    }
    private fun jsonWith(record: TranscriptSessionRecord, edit: (MutableMap<String, JsonElement>) -> Unit): String {
        val fields = (Json.parseToJsonElement(record.toJson()) as JsonObject).toMutableMap()
        edit(fields)
        return JsonObject(fields).toString()
    }

    @Test
    fun `saving then loading keeps the language`() = withDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val saved = record(language = "en")
        store.save(saved)
        assertEquals("en", assertNotNull(store.load(saved.id)).languageCode)
    }

    @Test
    fun `a conversation made by the live session carries its language`() {
        val made = TranscriptSessionRecord.make(emptyList(), { null }, UUID.randomUUID(), 1.0, null, TranscriptionEngineKind.WhisperKit, null, null, languageCode = "en")
        assertEquals("en", made.languageCode)
    }

    @Test
    fun `an older file without the language loads with none`() {
        val loaded = TranscriptSessionRecord.fromJson(jsonWith(record(language = "en")) { it.remove("languageCode") })
        assertNull(loaded.languageCode)
        assertEquals(1, loaded.segments.size)
        assertEquals("he", loaded.languageCode(orCurrent = "he"))
    }

    @Test
    fun `a damaged language reads as missing and the conversation stays`() {
        val loaded = TranscriptSessionRecord.fromJson(jsonWith(record(language = "en")) { it["languageCode"] = JsonArray(listOf(JsonPrimitive("x"))) })
        assertNull(loaded.languageCode)
        assertEquals(1, loaded.segments.size)
    }

    @Test
    fun `an empty or garbled language reads as missing and the conversation stays`() {
        for (bad in listOf("", "e n!", "english-language-code", "--", "en-", "-en")) {
            val loaded = TranscriptSessionRecord.fromJson(jsonWith(record(language = "en")) { it["languageCode"] = JsonPrimitive(bad) })
            assertNull(loaded.languageCode, bad)
            assertEquals(1, loaded.segments.size)
        }
    }

    @Test
    fun `the record's own language wins over the current one`() {
        assertEquals("en", record(language = "en").languageCode(orCurrent = "he"))
    }

    @Test
    fun `the export of an English conversation lists its numbers while the setting is Hebrew`() {
        val texts = (0 until TranscriptHistoryStore.NUMBERS_BLOCK_MINIMUM_LINES).map { if (it == 3) "Take three pills" else "Hello there $it" }
        val text = TranscriptHistoryStore.exportText(record(language = "en", texts = texts), utcOffsetAt = { 0 }, currentLanguageCode = "he")
        val block = text.split("\n\n").firstOrNull { it.startsWith("Numbers mentioned:") || it.startsWith("מספרים שנאמרו:") }
        assertTrue(block?.contains("three pills") == true, text)
    }

    @Test
    fun `autosave keeps the first saves language`() = withDirectory { dir ->
        val id = UUID.randomUUID()
        val store = TranscriptHistoryStore(dir)
        store.save(record(id, "en"))
        store.save(record(id, "he"))
        assertEquals("en", assertNotNull(store.load(id)).languageCode, "remembered path")
        val fresh = TranscriptHistoryStore(dir)
        fresh.save(record(id, "he"))
        assertEquals("en", assertNotNull(fresh.load(id)).languageCode, "file path")
    }

    @Test
    fun `a damaged model field in the file does not cost the conversation its language`() = withDirectory { dir ->
        val id = UUID.randomUUID()
        TranscriptHistoryStore(dir).save(record(id, "en"))
        val file = File(dir, dir.list()!!.first { it.endsWith(".json") })
        val fields = (Json.parseToJsonElement(file.readText()) as JsonObject).toMutableMap()
        fields["modelVariant"] = JsonArray(listOf(JsonPrimitive("x")))
        file.writeText(JsonObject(fields).toString())
        val fresh = TranscriptHistoryStore(dir)
        fresh.save(record(id, "he"))
        assertEquals("en", assertNotNull(fresh.load(id)).languageCode)
    }

    @Test
    fun `a conversation saved before the language existed stays without one`() = withDirectory { dir ->
        val id = UUID.randomUUID()
        TranscriptHistoryStore(dir).save(record(id, null))
        val fresh = TranscriptHistoryStore(dir)
        fresh.save(record(id, "he"))
        assertNull(assertNotNull(fresh.load(id)).languageCode)
    }

    @Test
    fun `rows and starred lines carry the language`() = withDirectory { dir ->
        val store = TranscriptHistoryStore(dir)
        val base = record(language = "en")
        store.save(base.copy(segments = listOf(base.segments[0].copy(isStarred = true))))
        assertEquals("en", store.listSummaries().first().languageCode)
        assertEquals("en", store.starredLines().first().languageCode)
    }
}
