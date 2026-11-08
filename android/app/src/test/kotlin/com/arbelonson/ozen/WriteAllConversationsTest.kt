package com.arbelonson.ozen

import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteAllConversationsTest {
    private fun record(startedAt: Double, text: String) = TranscriptSessionRecord(
        id = UUID.randomUUID(),
        startedAt = startedAt,
        endedAt = null,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = "small",
        inputName = null,
        segments = listOf(SavedSegment(UUID.randomUUID(), text, null, null, startedAt, true)),
    )

    private fun summary(record: TranscriptSessionRecord) = TranscriptSessionSummary(
        id = record.id,
        startedAt = record.startedAt,
        endedAt = null,
        segmentCount = 1,
        preview = "",
        engine = TranscriptionEngineKind.WhisperKit,
    )

    private fun saved(records: List<TranscriptSessionRecord>, unreadable: Set<UUID> = emptySet(), onLoad: (UUID) -> Unit = {}): SavedConversations =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SavedConversations::class.java)) { _, method, args ->
            when (method.name) {
                "list" -> records.map(::summary)
                "load" -> {
                    val id = args[0] as UUID
                    onLoad(id)
                    records.firstOrNull { it.id == id && id !in unreadable }
                }
                else -> error("unexpected ${method.name}")
            }
        } as SavedConversations

    private val newestFirst = listOf(record(176_400.0, "שלוש"), record(90_000.0, "שתיים"), record(3_661.0, "אחת"))

    @Test
    fun `the written text is what exportAllText gives for the same conversations`() {
        val out = StringBuilder()
        assertTrue(writeAllConversations(saved(newestFirst), true, "he", out))
        assertEquals(TranscriptHistoryStore.exportAllText(newestFirst, ::localOffset, true, false), out.toString())
    }

    @Test
    fun `a conversation is loaded only after the one before it was written`() {
        val out = StringBuilder()
        val lengthWhenLoaded = ArrayList<Int>()
        writeAllConversations(saved(newestFirst, onLoad = { lengthWhenLoaded.add(out.length) }), true, "he", out)
        assertEquals(3, lengthWhenLoaded.size)
        assertEquals(0, lengthWhenLoaded[0])
        assertTrue(lengthWhenLoaded[1] > 0, lengthWhenLoaded.toString())
        assertTrue(lengthWhenLoaded[2] > lengthWhenLoaded[1], lengthWhenLoaded.toString())
    }

    @Test
    fun `a conversation that cannot be loaded is skipped and nothing saved writes nothing`() {
        val gone = newestFirst[1].id
        val out = StringBuilder()
        assertTrue(writeAllConversations(saved(newestFirst, setOf(gone)), true, "he", out))
        assertEquals(TranscriptHistoryStore.exportAllText(newestFirst.filter { it.id != gone }, ::localOffset, true, false), out.toString())
        val none = StringBuilder()
        assertFalse(writeAllConversations(saved(emptyList()), true, "he", none))
        assertEquals("", none.toString())
    }
}
