package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

class ConversationSaverTest {
    private val folder = Files.createTempDirectory("ozen-conversation-saver").toFile()
    private val store = TranscriptHistoryStore(folder)
    private val writer = TranscriptHistoryWriter(store)
    private val scope = TestScope()
    private val lines = mutableListOf<TranscriptSegment>()
    private var listening = false
    private val settings = AppSettings.default
    private var clock = 1_800_000_000.0
    private var newConversations = 0
    private val saver = ConversationSaver(
        writer = writer,
        lines = { lines.toList() },
        isListening = { listening },
        settings = { settings },
        transcribing = { settings },
        speakerName = { null },
        inputName = { "Wired headset" },
        startNewConversation = { newConversations += 1 },
        scope = scope,
        now = { clock },
        io = EmptyCoroutineContext,
    )

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    private fun say(text: String, at: Double = clock) {
        lines += TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, startTimestamp = at, lastUpdateTimestamp = at)
    }

    private fun start() {
        listening = true
        saver.listeningChanged()
    }

    private fun stop() {
        listening = false
        saver.listeningChanged()
    }

    private fun saved() = writer.waitUntilIdle().let { store.listSummaries() }

    @Test
    fun `a conversation is saved when captions stop, ending then`() {
        start()
        say("Good morning")
        clock += 30
        stop()
        val conversation = saved().single()
        assertEquals("Good morning", conversation.preview)
        assertEquals(clock, conversation.endedAt)
    }

    @Test
    fun `while captions run, what was said is saved every twenty seconds and stays open`() {
        start()
        say("Good morning")
        scope.advanceTimeBy(19_000)
        scope.runCurrent()
        assertTrue(saved().isEmpty())
        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        val conversation = saved().single()
        assertEquals("Good morning", conversation.preview)
        assertNull(conversation.endedAt)
    }

    @Test
    fun `once captions stop the twenty-second saves stop too`() {
        start()
        say("Good morning")
        stop()
        val stopped = saved().single()
        say("Said after the stop")
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(stopped.segmentCount, saved().single().segmentCount)
    }

    @Test
    fun `a stopped conversation keeps its end when saved again, and is open again once captions resume`() {
        start()
        say("Good morning")
        clock += 30
        stop()
        val end = saved().single().endedAt
        clock += 600
        saver.save(ended = false)
        assertEquals(end, saved().single().endedAt)

        start()
        say("Good evening")
        saver.save(ended = false)
        val resumed = saved().single()
        assertNull(resumed.endedAt)
        assertEquals(2, resumed.segmentCount)
    }

    @Test
    fun `after twenty quiet minutes the saved conversation closes and new lines save separately, screen untouched`() {
        start()
        val quietSince = clock - 30 * 60
        say("Good morning", at = quietSince)

        assertTrue(saver.checkForConversationBreak())
        assertEquals(1, newConversations)
        assertEquals(quietSince, saved().single().endedAt)

        say("Good evening")
        saver.save(ended = false)
        val conversations = saved()
        assertEquals(setOf("Good morning", "Good evening"), conversations.map { it.preview }.toSet())
        assertEquals(1, conversations.single { it.preview == "Good evening" }.segmentCount)
        assertEquals(clock, conversations.single { it.preview == "Good evening" }.startedAt)
        assertEquals(2, lines.size)
        assertFalse(saver.checkForConversationBreak())
    }

    @Test
    fun `a conversation begun after a break keeps its first line's time when captions pause and resume`() {
        start()
        say("Good morning", at = clock - 30 * 60)
        assertTrue(saver.checkForConversationBreak())
        val firstLine = clock - 120
        say("Good evening", at = firstLine)
        stop()
        start()
        saver.save(ended = false)
        assertEquals(firstLine, saved().single { it.preview == "Good evening" }.startedAt)
    }

    @Test
    fun `the autosave closes a conversation after twenty quiet minutes instead of saving on into it`() {
        start()
        say("Good morning", at = clock - 30 * 60)
        scope.advanceTimeBy(20_001)
        scope.runCurrent()
        assertEquals(1, newConversations)
        assertEquals(clock - 30 * 60, saved().single().endedAt)
    }

    @Test
    fun `a memory warning saves what was said since the last autosave`() {
        start()
        say("The pill in the morning")
        assertTrue(saved().isEmpty())
        saver.memoryShort()
        assertEquals("The pill in the morning", saved().single().preview)
    }

    @Test
    fun `nothing is saved with saving conversations off`() {
        settings.saveHistory = false
        start()
        say("Not to be kept")
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        saver.memoryShort()
        stop()
        assertTrue(saved().isEmpty())
        assertFalse(folder.listFiles().orEmpty().any { it.name.endsWith(".json") })
    }

    @Test
    fun `a saved conversation names the engine, model and microphone that wrote it`() {
        settings.engine = TranscriptionEngineKind.HomeServer
        start()
        say("Good morning")
        stop()
        val record = assertNotNull(store.load(saved().single().id))
        assertEquals(TranscriptionEngineKind.HomeServer, record.engine)
        assertEquals(settings.modelDescription, record.modelVariant)
        assertEquals("Wired headset", record.inputName)
    }

    @Test
    fun `old conversations expire at a save, checked at most every six hours, and the ones still on screen stay`() {
        settings.historyRetention = HistoryRetention.Week
        val today = clock
        val monthAgo = clock - 30 * 86_400
        val old = oldConversation(monthAgo)
        clock = monthAgo
        start()
        say("A month ago")
        clock = today
        assertTrue(saver.checkForConversationBreak())
        scope.runCurrent()
        val conversations = saved()
        assertFalse(conversations.any { it.id == old })
        assertTrue(conversations.any { it.preview == "A month ago" })

        val another = oldConversation(monthAgo)
        clock += 6 * 3_600
        say("Hello")
        saver.save(ended = false)
        scope.runCurrent()
        assertTrue(saved().any { it.id == another })
        clock += 1
        saver.save(ended = false)
        scope.runCurrent()
        assertFalse(saved().any { it.id == another })
        assertEquals(setOf("A month ago", "Hello"), saved().map { it.preview }.toSet())
    }

    private fun oldConversation(at: Double): UUID {
        val record = TranscriptSessionRecord(
            startedAt = at,
            endedAt = at + 60,
            engine = TranscriptionEngineKind.WhisperKit,
            modelVariant = null,
            inputName = null,
            segments = listOf(SavedSegment(UUID.randomUUID(), "Long gone", null, null, at, true)),
        )
        assertTrue(store.save(record))
        return record.id
    }
}
