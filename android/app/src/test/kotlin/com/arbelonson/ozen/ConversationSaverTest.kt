package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.ProblemAudioStore
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
    private val clips = ProblemAudioStore(File(folder, "problem-audio"))
    private val saver = ConversationSaver(
        writer = writer,
        lines = { lines.toList() },
        isListening = { listening },
        settings = { settings },
        transcribing = { settings },
        speakerName = { null },
        inputName = { "Wired headset" },
        startNewConversation = { newConversations += 1 },
        setSaveHistory = { settings.saveHistory = it },
        scope = scope,
        now = { clock },
        io = EmptyCoroutineContext,
        problemAudio = clips,
    )

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    private fun clipAt(at: Double): String =
        clips.save(floatArrayOf(0.1f, 0.2f), 16_000, Instant.ofEpochMilli((at * 1_000).toLong()))!!.name

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
        clipAt(monthAgo)
        val todaysClip = clipAt(today)
        clock = monthAgo
        start()
        say("A month ago")
        clock = today
        assertTrue(saver.checkForConversationBreak())
        scope.runCurrent()
        val conversations = saved()
        assertFalse(conversations.any { it.id == old })
        assertTrue(conversations.any { it.preview == "A month ago" })
        assertEquals(listOf(todaysClip), clips.clips().map { it.name })

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

    @Test
    fun `deleting the conversation still being captioned keeps it deleted through autosaves and stopping`() {
        start()
        say("Between us")
        saver.save(ended = false)
        val deleted = saved().single().id
        assertTrue(saver.isCurrent(deleted))

        saver.delete(deleted)
        assertFalse(saver.isCurrent(deleted))
        scope.advanceTimeBy(20_001)
        scope.runCurrent()
        stop()
        assertTrue(saved().isEmpty())
        assertEquals(1, lines.size)

        start()
        say("How are you")
        saver.save(ended = false)
        val next = saved().single()
        assertEquals("How are you", next.preview)
        assertEquals(1, next.segmentCount)
    }

    @Test
    fun `a name given to the conversation still being captioned stays through autosaves and stopping`() {
        start()
        say("The doctor will see you now")
        saver.save(ended = false)
        val named = saved().single().id
        saver.rename(named, "  Doctor visit  ")
        assertEquals("Doctor visit", saved().single().title)

        say("Take two pills after dinner")
        scope.advanceTimeBy(20_001)
        scope.runCurrent()
        clock += 30
        stop()
        val conversation = saved().single()
        assertEquals("Doctor visit", conversation.title)
        assertEquals(2, conversation.segmentCount)

        saver.rename(named, " ")
        assertNull(saved().single().title)
    }

    @Test
    fun `a star given in History to a line still being captioned stays through autosaves and stopping`() {
        start()
        say("Take two pills after dinner")
        say("Call the pharmacy")
        saver.save(ended = false)
        val conversation = saved().single().id
        val pills = lines[0].id
        assertEquals(true, saver.toggleStar(conversation, pills))
        assertEquals(listOf(true, false), store.load(conversation)!!.segments.map { it.isStarred })

        say("See you next week")
        scope.advanceTimeBy(20_001)
        scope.runCurrent()
        clock += 30
        stop()
        assertEquals(listOf(true, false, false), store.load(conversation)!!.segments.map { it.isStarred })
        assertEquals(1, saved().single().starredCount)

        assertEquals(false, saver.toggleStar(conversation, pills))
        saver.save(ended = true)
        assertEquals(listOf(false, false, false), store.load(conversation)!!.segments.map { it.isStarred })
    }

    @Test
    fun `a caption line opens the saved conversation it belongs to, and none when it was said with saving off`() {
        start()
        say("Good morning", at = clock - 30 * 60)
        assertTrue(saver.checkForConversationBreak())
        say("Good evening")
        saver.save(ended = false)
        val morning = saved().single { it.preview == "Good morning" }.id
        val evening = saved().single { it.preview == "Good evening" }.id
        assertEquals(listOf(morning, evening, null), (0..2).map(saver::savedConversation))
        assertTrue(saver.isCurrent(evening))

        saver.switchSaving(false)
        say("A secret between us")
        assertEquals(listOf(null, null, null), (0..2).map(saver::savedConversation))
        saver.switchSaving(true)
        say("See you tomorrow")
        val tomorrow = saver.savedConversation(3)
        assertNotNull(tomorrow)
        assertTrue(saver.isCurrent(tomorrow))
        assertEquals(listOf(morning, evening, null), (0..2).map(saver::savedConversation))
    }

    @Test
    fun `a caption line of a deleted conversation opens nothing, nor any line while saves fail`() {
        start()
        say("Good morning")
        saver.save(ended = false)
        saver.delete(saved().single().id)
        assertNull(saver.savedConversation(0))
        say("Good evening")
        assertNotNull(saver.savedConversation(1))

        folder.deleteRecursively()
        folder.writeText("not a folder")
        saver.save(ended = false)
        assertNotNull(saver.lastSaveFailure)
        assertNull(saver.savedConversation(1))
    }

    @Test
    fun `deleting all conversations deletes every saved one, and lines already on screen stay out of the next`() {
        oldConversation(clock - 3_600)
        start()
        say("Good morning", at = clock - 30 * 60)
        assertTrue(saver.checkForConversationBreak())
        say("Good evening")
        saver.save(ended = false)
        assertEquals(3, saved().size)

        saver.deleteAll()
        assertTrue(saved().isEmpty())
        assertEquals(listOf(null, null), (0..1).map(saver::savedConversation))
        say("Good night")
        stop()
        assertEquals(listOf("Good night"), saved().map { it.preview })
    }

    @Test
    fun `a line starred while it is said shows starred and is saved starred`() {
        start()
        say("Take two pills after dinner")
        say("Call the pharmacy")
        assertEquals(true, saver.toggleLiveStar(lines[1].id))
        assertTrue(saver.isStarred(lines[1].id))
        assertFalse(saver.isStarred(lines[0].id))
        stop()
        assertEquals(listOf(false, true), store.load(saved().single().id)!!.segments.map { it.isStarred })

        assertEquals(false, saver.toggleLiveStar(lines[1].id))
        assertFalse(saver.isStarred(lines[1].id))
        assertEquals(listOf(false, false), store.load(saved().single().id)!!.segments.map { it.isStarred })
    }

    @Test
    fun `a line still on screen from a conversation closed by a long quiet is starred in that conversation`() {
        start()
        say("Good morning", at = clock - 30 * 60)
        assertTrue(saver.checkForConversationBreak())
        say("Good evening")
        saver.save(ended = false)

        assertEquals(true, saver.toggleLiveStar(lines[0].id))
        assertTrue(saver.isStarred(lines[0].id))
        val conversations = saved()
        assertEquals(1, conversations.single { it.preview == "Good morning" }.starredCount)
        assertEquals(0, conversations.single { it.preview == "Good evening" }.starredCount)
        saver.save(ended = false)
        assertEquals(0, saved().single { it.preview == "Good evening" }.starredCount)
    }

    @Test
    fun `with saving off a line starred while it is said still shows starred`() {
        settings.saveHistory = false
        start()
        say("Off the record")
        assertEquals(true, saver.toggleLiveStar(lines[0].id))
        assertTrue(saver.isStarred(lines[0].id))
        stop()
        assertTrue(saved().isEmpty())
    }

    @Test
    fun `a star given to a line of an earlier conversation is written to that conversation`() {
        val earlier = oldConversation(clock - 3_600)
        val line = store.load(earlier)!!.segments.single().id
        start()
        say("Something else entirely")
        saver.save(ended = false)

        assertEquals(true, saver.toggleStar(earlier, line))
        assertTrue(store.load(earlier)!!.segments.single().isStarred)
        assertTrue(saved().none { it.id != earlier && it.starredCount > 0 })
        assertNull(saver.toggleStar(earlier, UUID.randomUUID()))
    }

    @Test
    fun `a conversation open for reading is not deleted as old until it is closed`() {
        settings.historyRetention = HistoryRetention.Week
        val reading = oldConversation(clock - 30 * 86_400)
        saver.opened(reading)
        start()
        say("Hello")
        saver.save(ended = false)
        scope.runCurrent()
        assertTrue(saved().any { it.id == reading })

        saver.doneWith(reading)
        clock += 6 * 3_600 + 1
        saver.save(ended = false)
        scope.runCurrent()
        assertFalse(saved().any { it.id == reading })
    }

    @Test
    fun `lines said while saving was off stay out of every saved conversation after it is switched back on`() {
        start()
        say("How is everyone", at = clock - 20)
        saver.switchSaving(false)
        assertFalse(settings.saveHistory)
        say("A secret between us", at = clock - 10)
        saver.switchSaving(true)
        assertTrue(settings.saveHistory)
        say("See you tomorrow")
        saver.save(ended = true)
        val said = saved().mapNotNull { store.load(it.id) }.flatMap { it.segments }.map { it.text }
        assertTrue("How is everyone" in said, said.toString())
        assertTrue("See you tomorrow" in said, said.toString())
        assertFalse("A secret between us" in said, said.toString())
    }

    @Test
    fun `switching saving off keeps what was said until then, ending at its last line`() {
        start()
        say("Good morning", at = clock - 20)
        saver.switchSaving(false)
        val kept = saved().single()
        assertEquals("Good morning", kept.preview)
        assertEquals(clock - 20, kept.endedAt)
    }

    @Test
    fun `old conversations are deleted at once when asked, never the one on screen`() {
        val old = oldConversation(clock - 40 * 86_400)
        start()
        say("Today")
        saver.save(ended = false)
        val now = clock + 60 * 86_400
        val oldClip = clipAt(clock - 40 * 86_400)
        val recentClip = clipAt(now - 10 * 86_400)
        assertEquals(0, runBlocking { saver.deleteExpired(now) })
        assertEquals(setOf(oldClip, recentClip), clips.clips().map { it.name }.toSet())

        settings.historyRetention = HistoryRetention.Month
        assertEquals(1, runBlocking { saver.deleteExpired(now) })
        assertEquals(listOf(recentClip), clips.clips().map { it.name })
        val left = saved()
        assertFalse(left.any { it.id == old })
        assertEquals(listOf("Today"), left.map { it.preview })
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
