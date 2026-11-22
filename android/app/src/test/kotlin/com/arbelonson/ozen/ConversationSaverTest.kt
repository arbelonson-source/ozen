package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.FileTranscriptRecord
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.HistorySave
import com.arbelonson.ozen.core.ProblemAudioStore
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.TranscribedLine
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.WordFix
import com.arbelonson.ozen.core.WordFixResult
import com.arbelonson.ozen.core.WordFixes
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
    private val names = mutableMapOf<Int, String>()
    private val saver = ConversationSaver(
        writer = writer,
        lines = { lines.toList() },
        isListening = { listening },
        settings = { settings },
        transcribing = { settings },
        speakerName = { it.speakerClusterID?.let(names::get) },
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

    /** The caption screen's own step, as `CaptionPipeline.correctWord` makes it: whether the line on screen changed. */
    private fun fixOnScreen(line: UUID, fix: WordFix): Boolean {
        val index = lines.indexOfLast { it.id == line }
        if (index < 0) return false
        val fixed = WordFixes.apply(listOf(fix), lines[index].text)
        if (fixed == lines[index].text) return false
        lines[index] = lines[index].copy(text = fixed)
        return true
    }

    @Test
    fun `a saved conversation keeps the zone the phone was in when it started`() {
        var offset = 7_200
        val abroad = ConversationSaver(
            writer = writer, lines = { lines.toList() }, isListening = { listening }, settings = { settings }, transcribing = { settings },
            speakerName = { null }, inputName = { null }, startNewConversation = {}, setSaveHistory = {}, scope = scope,
            now = { clock }, io = EmptyCoroutineContext, utcOffsetAt = { offset },
        )
        listening = true
        abroad.listeningChanged()
        say("Take three pills")
        abroad.save(ended = false)
        offset = 0
        listening = false
        abroad.listeningChanged()
        writer.waitUntilIdle()
        assertEquals(7_200, store.load(store.listSummaries().single().id)?.utcOffsetSeconds)
    }

    @Test
    fun `a saved conversation keeps the language the captions were started in, not the setting that changed meanwhile`() {
        val switched = ConversationSaver(
            writer = writer, lines = { lines.toList() }, isListening = { listening }, settings = { settings }, transcribing = { settings },
            speakerName = { null }, inputName = { null }, startNewConversation = {}, setSaveHistory = {}, scope = scope,
            now = { clock }, io = EmptyCoroutineContext, spokenLanguage = { "en" },
        )
        listening = true
        switched.listeningChanged()
        say("Take three pills")
        listening = false
        switched.listeningChanged()
        writer.waitUntilIdle()
        val record = assertNotNull(store.load(store.listSummaries().single().id))
        assertEquals("en", record.languageCode)
        assertEquals("he", settings.languageCode)
    }

    @Test
    fun `a conversation keeps the language it was first saved with when the spoken language changes later`() {
        var language = "en"
        val pinned = ConversationSaver(
            writer = writer, lines = { lines.toList() }, isListening = { listening }, settings = { settings }, transcribing = { settings },
            speakerName = { null }, inputName = { null }, startNewConversation = {}, setSaveHistory = {}, scope = scope,
            now = { clock }, io = EmptyCoroutineContext, spokenLanguage = { language },
        )
        listening = true
        pinned.listeningChanged()
        say("Take three pills")
        scope.advanceTimeBy(20_001)
        scope.runCurrent()
        language = "fr"
        say("And rest")
        listening = false
        pinned.listeningChanged()
        writer.waitUntilIdle()
        val record = assertNotNull(store.load(store.listSummaries().single().id))
        assertEquals("en", record.languageCode)
        assertEquals(2, record.segments.size)
    }

    @Test
    fun `a saved conversation has the setting's language when nothing says otherwise`() {
        start()
        say("Good morning")
        stop()
        assertEquals(settings.languageCode, assertNotNull(store.load(saved().single().id)).languageCode)
    }

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
    fun `a name given to a voice is written into the closed conversation still on screen and the open one, their times kept`() {
        start()
        val quietSince = clock - 30 * 60
        val voice = { text: String, at: Double ->
            TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, speakerClusterID = 0, startTimestamp = at, lastUpdateTimestamp = at)
        }
        lines += voice("Good morning", quietSince)
        assertTrue(saver.checkForConversationBreak())
        lines += voice("Good evening", clock)
        saver.save(ended = false)
        assertTrue(saved().all { it.speakerNames.isEmpty() })

        names[0] = "Dana"
        saver.speakerLabelsChanged()
        val conversations = saved()
        assertEquals(listOf(listOf("Dana"), listOf("Dana")), conversations.map { it.speakerNames })
        val morning = conversations.single { it.preview == "Good morning" }
        assertEquals(quietSince, morning.endedAt)
        assertEquals(1, morning.segmentCount)
        val evening = conversations.single { it.preview == "Good evening" }
        assertNull(evening.endedAt)
        assertEquals(1, evening.segmentCount)
    }

    @Test
    fun `clearing the screen keeps what was said as one ended conversation, and the next words start a new one from the top`() {
        start()
        say("Hello", at = clock - 60)
        say("How are you", at = clock - 30)
        saver.clear { lines.clear() }
        assertEquals(clock, saved().single().endedAt)
        clock += 5
        say("New topic")
        saver.save(ended = false)
        val conversations = saved()
        assertEquals(2, conversations.single { it.preview == "Hello" }.segmentCount)
        val next = conversations.single { it.preview == "New topic" }
        assertEquals(1, next.segmentCount)
        assertNull(next.endedAt)
    }

    @Test
    fun `a clear promised to keep the lines in History keeps them on screen when that save fails, and clears them once she accepts the loss`() {
        start()
        say("Hello", at = clock - 60)
        folder.deleteRecursively()
        folder.writeText("not a folder")
        var cleared = 0
        assertFalse(saver.clear(keepingHistory = true) { cleared += 1; lines.clear() })
        assertEquals(0, cleared)
        assertEquals(1, lines.size)
        assertNotNull(saver.lastSaveFailure)
        assertTrue(saver.clear(keepingHistory = false) { cleared += 1; lines.clear() })
        assertEquals(1, cleared)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a clear with nothing said yet goes through even while saves fail`() {
        start()
        folder.deleteRecursively()
        folder.writeText("not a folder")
        assertTrue(saver.clear(keepingHistory = true) { lines.clear() })
    }

    @Test
    fun `clearing after a break closed an earlier conversation still starts the next one from the first line on the screen`() {
        start()
        say("Good morning", at = clock - 30 * 60)
        assertTrue(saver.checkForConversationBreak())
        say("Hello", at = clock - 60)
        saver.clear { lines.clear() }
        clock += 5
        say("New topic")
        saver.save(ended = false)
        val next = saved().single { it.preview == "New topic" }
        assertEquals(1, next.segmentCount)
        assertEquals(next.id, saver.savedConversation(0))
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
    fun `old conversations expire with saving off too when the app comes back, at most every six hours`() {
        settings.historyRetention = HistoryRetention.Week
        settings.saveHistory = false
        val monthAgo = clock - 30 * 86_400
        val old = oldConversation(monthAgo)
        val oldClip = clipAt(monthAgo)
        val recent = oldConversation(clock - 86_400)

        saver.deleteExpiredIfDue()
        scope.runCurrent()
        assertEquals(listOf(recent), saved().map { it.id })
        assertFalse(old in saved().map { it.id })
        assertFalse(oldClip in clips.clips().map { it.name })

        val another = oldConversation(monthAgo)
        clock += 6 * 3_600
        saver.deleteExpiredIfDue()
        scope.runCurrent()
        assertTrue(saved().any { it.id == another })
        clock += 1
        saver.deleteExpiredIfDue()
        scope.runCurrent()
        assertEquals(listOf(recent), saved().map { it.id })
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
    fun `the conversation of a caption line is still known while saves fail, so a marked problem can name it`() {
        start()
        say("Good morning")
        saver.save(ended = false)
        val morning = saved().single().id
        say("Good evening")
        folder.deleteRecursively()
        folder.writeText("not a folder")
        saver.save(ended = false)
        assertNotNull(saver.lastSaveFailure)
        assertNull(saver.savedConversation(0))
        assertEquals(morning, saver.conversationOfLine(0))
        assertEquals(saver.currentId, saver.conversationOfLine(1))
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

    // History deletes everything from its own thread, after the caption
    // screen's step that starts the next conversation. A conversation the
    // screen started and saved right after that step, before the delete
    // reached the disk, was deleted with the rest, and with captions stopped
    // there no later save brought it back.
    @Test
    fun `a conversation started right after History deletes all is kept`() {
        var next: () -> Unit = {}
        val history = ConversationSaver(
            writer = writer, lines = { lines.toList() }, isListening = { listening }, settings = { settings }, transcribing = { settings },
            speakerName = { null }, inputName = { null }, startNewConversation = {}, setSaveHistory = { settings.saveHistory = it },
            scope = scope, now = { clock }, io = EmptyCoroutineContext,
            onMainThread = { step ->
                step()
                next()
            },
        )
        listening = true
        history.listeningChanged()
        say("Good evening")
        history.save(ended = false)
        assertEquals(1, saved().size)
        next = {
            next = {}
            say("Good night")
            listening = false
            history.listeningChanged()
        }

        history.deleteAll()
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
    fun `a word fixed in History on a line of the conversation being captioned says whether the screen took it`() {
        start()
        say("I saw aval")
        saver.save(ended = false)
        val conversation = saved().single().id
        val line = lines[0].id

        assertEquals(WordFixResult.LineChanged, saver.fixLine(conversation, line, WordFix("aval", "Avi")) { _, _ -> false })
        assertEquals("I saw aval", store.load(conversation)!!.segments.single().text)

        assertEquals(
            WordFixResult.Saved,
            saver.fixLine(conversation, line, WordFix("aval", "Avi")) { _, _ ->
                lines[0] = lines[0].copy(text = "I saw Avi")
                true
            },
        )
        assertEquals("I saw Avi", store.load(conversation)!!.segments.single().text)
    }

    @Test
    fun `a word fixed in History on an earlier conversation is written to the text saved now and never asks the screen`() {
        val earlier = oldConversation(clock - 3_600)
        val line = store.load(earlier)!!.segments.single().id
        store.fixText(line, earlier, "I saw aval today")
        start()
        say("Something else entirely")
        saver.save(ended = false)

        var asked = false
        assertEquals(WordFixResult.Saved, saver.fixLine(earlier, line, WordFix("aval", "Avi")) { _, _ -> asked = true; false })
        assertFalse(asked)
        assertEquals("I saw Avi today", store.load(earlier)!!.segments.single().text)
        assertEquals(WordFixResult.LineChanged, saver.fixLine(earlier, UUID.randomUUID(), WordFix("aval", "Avi")) { _, _ -> true })
    }

    // Fix a word in History keeps the words the line had when it opened,
    // and the line can be heard again meanwhile. A fix of a word it no
    // longer has changed nothing, yet it was taken as done: the dialog
    // closed and the fix was kept for next time.
    @Test
    fun `a word fixed in History that a line still being captioned no longer has is refused`() {
        start()
        say("I saw Avi today")
        saver.save(ended = false)
        val conversation = saved().single().id

        assertEquals(WordFixResult.LineChanged, saver.fixLine(conversation, lines[0].id, WordFix("aval", "Avi"), ::fixOnScreen))
        assertEquals("I saw Avi today", store.load(conversation)!!.segments.single().text)
    }

    // History reads the line off the main thread and the screen fixes it on
    // the main thread; the engine can rewrite the line in between. Checking
    // the text again afterwards compared it with that early read, so a fix
    // the screen did make was refused when the rewrite had put back the
    // very word it fixed. Only the screen's own step can tell.
    @Test
    fun `a word fixed in History is taken when the screen's own step changed the line, whatever the engine rewrote before it`() {
        start()
        say("I saw Avi")
        saver.save(ended = false)
        val conversation = saved().single().id
        val line = lines[0].id

        assertEquals(
            WordFixResult.Saved,
            saver.fixLine(conversation, line, WordFix("aval", "Avi")) { id, fix ->
                lines[0] = lines[0].copy(text = "I saw aval")
                fixOnScreen(id, fix)
            },
        )
        assertEquals("I saw Avi", lines[0].text)
        assertEquals("I saw Avi", store.load(conversation)!!.segments.single().text)
    }

    // History asks from its own thread, and a long quiet can close the
    // conversation on the main thread before the screen's step fixes the
    // line. The fix was then saved into the next conversation, which has
    // no lines yet, and was called done while History kept the old words.
    @Test
    fun `a word fixed in History on a line a long quiet closed meanwhile is saved in the conversation it belongs to`() {
        start()
        val quietSince = clock - 30 * 60
        say("I saw aval", at = quietSince)
        saver.save(ended = false)
        val conversation = saved().single().id
        val line = lines[0].id

        assertEquals(
            WordFixResult.Saved,
            saver.fixLine(conversation, line, WordFix("aval", "Avi")) { id, fix ->
                assertTrue(saver.checkForConversationBreak())
                fixOnScreen(id, fix)
            },
        )
        assertEquals("I saw Avi", lines[0].text)
        val closed = store.load(conversation)!!
        assertEquals(listOf("I saw Avi"), closed.segments.map { it.text })
        assertEquals(quietSince, closed.endedAt)
        assertEquals(listOf(conversation), saved().map { it.id })
    }

    // The screen fixed the line in its own step, and the long quiet that
    // closed its conversation meanwhile made it look like a line of an
    // earlier conversation afterwards: the screen was asked again and fixed
    // the words a second time.
    @Test
    fun `a word fixed in History on a line a long quiet closed meanwhile is fixed on screen once`() {
        start()
        val quietSince = clock - 30 * 60
        say("I saw Cohen", at = quietSince)
        saver.save(ended = false)
        val conversation = saved().single().id
        val line = lines[0].id

        assertEquals(
            WordFixResult.Saved,
            saver.fixLineEverywhere(conversation, line, WordFix("Cohen", "Avi Cohen")) { id, fix ->
                if (saver.isCurrent(conversation)) assertTrue(saver.checkForConversationBreak())
                fixOnScreen(id, fix)
            },
        )
        assertEquals("I saw Avi Cohen", lines[0].text)
        assertEquals(listOf("I saw Avi Cohen"), store.load(conversation)!!.segments.map { it.text })
    }

    @Test
    fun `a word fixed in History on a line of an earlier conversation still on screen is fixed there too`() {
        start()
        say("I saw Cohen", at = clock - 30 * 60)
        saver.save(ended = false)
        val conversation = saved().single().id
        assertTrue(saver.checkForConversationBreak())

        assertEquals(WordFixResult.Saved, saver.fixLineEverywhere(conversation, lines[0].id, WordFix("Cohen", "Avi Cohen"), ::fixOnScreen))
        assertEquals("I saw Avi Cohen", lines[0].text)
        assertEquals(listOf("I saw Avi Cohen"), store.load(conversation)!!.segments.map { it.text })
    }

    // History asks from its own thread and the screen fixes the line in a
    // step of the main thread. What to save was read on History's thread
    // after that step, so captions cleared just then had the fix called not
    // saved, and a save written after the clear's could open the ended
    // conversation again.
    @Test
    fun `a word fixed in History is saved with the lines the screen fixed, whatever the main thread does next`() {
        var next: () -> Unit = {}
        val history = ConversationSaver(
            writer = writer, lines = { lines.toList() }, isListening = { listening }, settings = { settings }, transcribing = { settings },
            speakerName = { null }, inputName = { "Wired headset" }, startNewConversation = {}, setSaveHistory = { settings.saveHistory = it },
            scope = scope, now = { clock }, io = EmptyCoroutineContext,
            onMainThread = { step ->
                step()
                next()
            },
        )
        listening = true
        history.listeningChanged()
        say("I saw aval")
        history.save(ended = false)
        val conversation = saved().single().id
        next = { history.clear { lines.clear() } }

        assertEquals(WordFixResult.Saved, history.fixLine(conversation, lines[0].id, WordFix("aval", "Avi"), ::fixOnScreen))
        val kept = store.load(conversation)!!
        assertEquals(listOf("I saw Avi"), kept.segments.map { it.text })
        assertNotNull(kept.endedAt, "the clear ended the conversation")
        assertEquals(listOf(conversation), saved().map { it.id })
    }

    // The same for a line a long quiet closed while the screen fixed it:
    // its conversation waited for room, and History was told the fix was
    // not saved though it was written once there was room.
    @Test
    fun `a word fixed in History on a line a long quiet closed meanwhile counts as saved while it waits for room`() {
        start()
        say("I saw aval", at = clock - 30 * 60)
        saver.save(ended = false)
        val conversation = saved().single().id
        val line = lines[0].id

        assertTrue(folder.setWritable(false))
        val kept = try {
            saver.fixLine(conversation, line, WordFix("aval", "Avi")) { id, fix ->
                assertTrue(saver.checkForConversationBreak())
                fixOnScreen(id, fix)
            }
        } finally {
            folder.setWritable(true)
        }
        say("Something else entirely")
        saver.save(ended = false)
        assertEquals(listOf("I saw Avi"), store.load(conversation)!!.segments.map { it.text })
        assertEquals(WordFixResult.Saved, kept, "History calls a fix waiting for room saved")
    }

    // History's dialog says a fix it was told was not saved can be tried
    // again. One that had to wait for room was kept anyway and written once
    // there was room, so the tap that tried again fixed the words twice.
    @Test
    fun `a word fixed in History that waits for room is called saved, so its words are fixed once`() {
        val earlier = oldConversation(clock - 3_600)
        val line = store.load(earlier)!!.segments.single().id
        store.fixText(line, earlier, "I saw Cohen today")
        val fix = WordFix("Cohen", "Avi Cohen")

        assertTrue(folder.setWritable(false))
        val kept = try {
            saver.fixLineEverywhere(earlier, line, fix, ::fixOnScreen)
        } finally {
            folder.setWritable(true)
        }
        if (kept != WordFixResult.Saved) saver.fixLineEverywhere(earlier, line, fix, ::fixOnScreen)
        start()
        say("Something else entirely")
        saver.save(ended = false)
        assertEquals("I saw Avi Cohen today", store.load(earlier)!!.segments.single().text)
        assertEquals(WordFixResult.Saved, kept, "History calls a fix waiting for room saved")
    }

    // A replay hears the same speech again and takes the lines it rewrites
    // off the screen, and every save of the conversation being captioned is
    // written from the lines on screen. A fix of such a line went to the
    // file alone, said it was done, and the next save dropped it with the
    // line.
    @Test
    fun `a word fixed in History on a line a replay took off the screen is refused, as the next save drops that line`() {
        start()
        say("I saw aval")
        saver.save(ended = false)
        val conversation = saved().single().id
        val gone = lines[0].id
        lines.clear()
        say("I saw aval today")

        assertEquals(WordFixResult.LineChanged, saver.fixLine(conversation, gone, WordFix("aval", "Avi"), ::fixOnScreen))
        assertEquals(listOf("I saw aval"), store.load(conversation)!!.segments.map { it.text })
        saver.save(ended = false)
        assertEquals(listOf("I saw aval today"), store.load(conversation)!!.segments.map { it.text })
    }

    // The same with a star: History wrote it to the file alone and said it
    // was starred, and the next save, written from the lines on screen,
    // dropped the star with the line.
    @Test
    fun `a star given in History to a line a replay took off the screen is refused, as the next save drops that line`() {
        start()
        say("Take two pills after dinner")
        saver.save(ended = false)
        val conversation = saved().single().id
        val gone = lines[0].id
        lines.clear()
        say("Take two pills after dinner tonight")

        assertNull(saver.toggleStar(conversation, gone))
        assertFalse(saver.isStarred(gone))
        assertEquals(listOf(false), store.load(conversation)!!.segments.map { it.isStarred })
        saver.save(ended = false)
        assertEquals(listOf("Take two pills after dinner tonight"), store.load(conversation)!!.segments.map { it.text })
        assertEquals(0, saved().single().starredCount)
    }

    // History said the line changed or was gone, though the line was fine
    // and the same fix went through once the file could be read.
    @Test
    fun `a word fixed in History on an earlier conversation that cannot be read just now says so, and goes through when tried again`() {
        val earlier = oldConversation(clock - 3_600)
        val line = store.load(earlier)!!.segments.single().id
        store.fixText(line, earlier, "I saw aval today")
        val file = folder.listFiles()!!.single { it.name.equals("$earlier.json", ignoreCase = true) }

        assertTrue(file.setReadable(false, false))
        val refused = try {
            saver.fixLineEverywhere(earlier, line, WordFix("aval", "Avi"), ::fixOnScreen)
        } finally {
            file.setReadable(true)
        }
        assertEquals(WordFixResult.Unreadable, refused)
        assertEquals("I saw aval today", store.load(earlier)!!.segments.single().text)
        assertEquals(WordFixResult.Saved, saver.fixLineEverywhere(earlier, line, WordFix("aval", "Avi"), ::fixOnScreen))
        assertEquals("I saw Avi today", store.load(earlier)!!.segments.single().text)
    }

    // That read counted as a save that failed: the caption screen said the
    // phone was probably full, and its lines no longer opened their
    // conversation, though nothing had failed to save.
    @Test
    fun `a word fixed in History on an earlier conversation that cannot be read just now is not taken for a full phone`() {
        val earlier = oldConversation(clock - 3_600)
        val line = store.load(earlier)!!.segments.single().id
        store.fixText(line, earlier, "I saw aval today")
        val file = folder.listFiles()!!.single { it.name.equals("$earlier.json", ignoreCase = true) }
        start()
        say("Something else entirely")
        saver.save(ended = false)
        writer.waitUntilIdle()

        assertTrue(file.setReadable(false, false))
        val refused = try {
            saver.fixLineEverywhere(earlier, line, WordFix("aval", "Avi"), ::fixOnScreen)
        } finally {
            file.setReadable(true)
        }
        assertEquals(WordFixResult.Unreadable, refused)
        assertNull(saver.lastSaveFailure)
        assertEquals(saver.currentId, saver.savedConversation(0))
    }

    // The iPhone fixes the words a fix still waiting for room left. Here a
    // second fix of that line, while its conversation couldn't be read just
    // now, said to try again, though it could wait for room like the first.
    @Test
    fun `a word fixed in History on a line whose earlier fix waits for room is saved, even while its conversation cannot be read just now`() {
        val earlier = oldConversation(clock - 3_600)
        val line = store.load(earlier)!!.segments.single().id
        store.fixText(line, earlier, "I saw aval and Dan")
        val file = folder.listFiles()!!.single { it.name.equals("$earlier.json", ignoreCase = true) }

        assertTrue(folder.setWritable(false))
        val kept = try {
            val first = saver.fixLineEverywhere(earlier, line, WordFix("aval", "Avi"), ::fixOnScreen)
            assertTrue(file.setReadable(false, false))
            listOf(first, saver.fixLineEverywhere(earlier, line, WordFix("Dan", "Dana"), ::fixOnScreen))
        } finally {
            file.setReadable(true)
            folder.setWritable(true)
        }
        assertEquals(listOf(WordFixResult.Saved, WordFixResult.Saved), kept)
        start()
        say("Something else entirely")
        saver.save(ended = false)
        writer.waitUntilIdle()
        assertEquals("I saw Avi and Dana", store.load(earlier)!!.segments.single().text)
        assertNull(saver.lastSaveFailure)
    }

    // The caption screen's fix of a line a long quiet closed tries each
    // closed conversation in turn, and each one without the line counted as
    // a save that worked: after History refused a name on a full phone, a
    // fix of a line whose conversation History had deleted took the warning
    // away, and the caption lines opened their conversations again, while
    // the phone was still full.
    @Test
    fun `a word fixed on the caption screen that no closed conversation has leaves a save that failed before reported`() {
        start()
        say("Good morning", at = clock - 60 * 60)
        val morning = saver.currentId
        assertTrue(saver.checkForConversationBreak())
        say("Good afternoon", at = clock - 30 * 60)
        val afternoon = saver.currentId
        assertTrue(saver.checkForConversationBreak())
        say("Good evening")
        saver.save(ended = false)
        saver.delete(morning)

        assertTrue(folder.setWritable(false))
        try {
            assertFalse(saver.rename(afternoon, "Lunch"))
            val full = assertNotNull(saver.lastSaveFailure)
            lines[0] = lines[0].copy(text = "Good morning, Avi")
            saver.fixLiveLine(lines[0].id, lines[0].text)
            assertEquals(full, saver.lastSaveFailure, "nothing was written, so the phone is still full")
            assertNull(saver.savedConversation(1))
        } finally {
            folder.setWritable(true)
        }
        lines[1] = lines[1].copy(text = "Good afternoon, Dana")
        saver.fixLiveLine(lines[1].id, lines[1].text)
        assertNull(saver.lastSaveFailure, "the line's words were written, so there is room")
        assertEquals(afternoon, saver.savedConversation(1))
        assertEquals(listOf("Good afternoon, Dana"), store.load(afternoon)!!.segments.map { it.text })
    }

    @Test
    fun `a word fixed in History that a line of an earlier conversation no longer has is refused`() {
        val earlier = oldConversation(clock - 3_600)
        val line = store.load(earlier)!!.segments.single().id

        assertEquals(WordFixResult.LineChanged, saver.fixLine(earlier, line, WordFix("aval", "Avi")) { _, _ -> true })
        assertEquals("Long gone", store.load(earlier)!!.segments.single().text)
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

    // The star was taken off on the caption screen while the phone was full,
    // so the file kept it and the starred lines list still showed it. Its
    // Remove the star, as a tap, put the star back on the line.
    @Test
    fun `Remove the star from the starred lines list takes it off a line still being captioned whose unstarring waited for room`() {
        start()
        say("Take two pills after dinner")
        assertEquals(true, saver.toggleLiveStar(lines[0].id))
        val conversation = saver.currentId
        assertTrue(folder.setWritable(false))
        try {
            assertEquals(false, saver.toggleLiveStar(lines[0].id))
            assertNull(saver.toggleStar(conversation, lines[0].id, to = false), "still full: not saved, and said so")
            assertFalse(saver.isStarred(lines[0].id))
        } finally {
            folder.setWritable(true)
        }
        assertEquals(listOf(true), store.load(conversation)!!.segments.map { it.isStarred })

        assertEquals(false, saver.toggleStar(conversation, lines[0].id, to = false))
        assertFalse(saver.isStarred(lines[0].id))
        assertEquals(listOf(false), store.load(conversation)!!.segments.map { it.isStarred })
    }

    // The starred lines list shows the files' stars. Its Remove the star,
    // as a tap following the waiting version (no star), put the star back.
    @Test
    fun `Remove the star from the starred lines list takes the star off a line whose version waiting for room has none`() {
        start()
        say("Good morning", at = clock - 30 * 60)
        assertEquals(true, saver.toggleLiveStar(lines[0].id))
        val morning = saver.currentId
        assertTrue(folder.setWritable(false))
        try {
            assertEquals(false, saver.toggleLiveStar(lines[0].id))
            assertTrue(saver.checkForConversationBreak())
        } finally {
            folder.setWritable(true)
        }
        assertEquals(listOf(true), store.load(morning)!!.segments.map { it.isStarred })

        assertEquals(false, saver.toggleStar(morning, lines[0].id, to = false))
        assertEquals(listOf(false), store.load(morning)!!.segments.map { it.isStarred })
        assertEquals(listOf(false), saver.load(morning)!!.segments.map { it.isStarred })
    }

    @Test
    fun `a star History cannot save leaves the line as it was, still being captioned or not`() {
        val earlier = oldConversation(clock - 3_600)
        val earlierLine = store.load(earlier)!!.segments.single().id
        start()
        say("Take two pills after dinner")
        saver.save(ended = false)
        val conversation = saved().single { it.id != earlier }.id
        val pills = lines[0].id

        assertTrue(folder.setWritable(false))
        try {
            assertNull(saver.toggleStar(conversation, pills))
            assertFalse(saver.isStarred(pills))
            assertNull(saver.toggleStar(earlier, earlierLine))
            assertNotNull(saver.lastSaveFailure)
        } finally {
            folder.setWritable(true)
        }

        assertFalse(store.load(earlier)!!.segments.single().isStarred)
        assertEquals(true, saver.toggleStar(earlier, earlierLine))
        assertEquals(listOf(false), store.load(conversation)!!.segments.map { it.isStarred }, "the save left waiting carries no star either")
        assertEquals(true, saver.toggleStar(conversation, pills))
        assertEquals(listOf(true), store.load(conversation)!!.segments.map { it.isStarred })
    }

    @Test
    fun `a star saved on a line being captioned counts as saved while another conversation still waits for room`() {
        val earlier = oldConversation(clock - 3_600)
        val waiting = store.load(earlier)!!.copy(endedAt = clock - 3_500)
        val earlierFile = folder.listFiles()!!.single { it.name.equals("$earlier.json", ignoreCase = true) }
        earlierFile.delete()
        File(earlierFile, "in-the-way").apply { parentFile.mkdirs() }.writeText("x")
        assertFalse(writer.saveNow(waiting))
        start()
        say("Take two pills after dinner")
        saver.save(ended = false)
        val conversation = saved().single { it.id != earlier }.id
        assertNotNull(saver.lastSaveFailure)

        assertEquals(true, saver.toggleStar(conversation, lines[0].id))
        assertTrue(saver.isStarred(lines[0].id))
        assertEquals(listOf(true), store.load(conversation)!!.segments.map { it.isStarred })
    }

    @Test
    fun `a star given on the caption screen while saves fail still shows, and is saved once there is room`() {
        start()
        say("Take two pills after dinner")
        saver.save(ended = false)
        val conversation = saved().single().id
        val pills = lines[0].id
        assertTrue(folder.setWritable(false))
        try {
            assertTrue(saver.toggleLiveStar(pills))
            assertTrue(saver.isStarred(pills))
        } finally {
            folder.setWritable(true)
        }
        saver.save(ended = false)
        assertEquals(listOf(true), store.load(conversation)!!.segments.map { it.isStarred })
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

    private fun recording() = FileTranscriptRecord.make("Lecture.wav", listOf(TranscribedLine(0.0, 2.0, "Good morning")), clock, 60.0, "ozen-turbo")

    @Test
    fun `a transcribed recording goes into History under its own id while saving is on`() {
        val record = recording()
        assertEquals(HistorySave.Saved(record.id), saver.saveRecording(record))
        assertEquals(listOf(record.id), saved().map { it.id })
        assertEquals(listOf("Good morning"), store.load(record.id)!!.segments.map { it.text })
    }

    @Test
    fun `a transcribed recording is not saved while saving is off, and that is not called a failure`() {
        settings.saveHistory = false
        assertEquals(HistorySave.Off, saver.saveRecording(recording()))
        assertTrue(saved().isEmpty())
        assertNull(saver.lastSaveFailure)
    }

    @Test
    fun `a transcribed recording the phone refuses to write is a failed save, not saving being off`() {
        folder.deleteRecursively()
        folder.writeText("not a folder")
        assertEquals(HistorySave.Failed, saver.saveRecording(recording()))
        assertNotNull(saver.lastSaveFailure)
    }

    /**
     * The caption screen's main thread, and History's own thread, which
     * hands the main thread a step and waits for it, as `CaptionSession`
     * does. [fromHistory] returns once History's work is done or waits for
     * the main thread, so History can act in the middle of a step of the
     * main thread wherever the saver does that work.
     */
    private class Threads : AutoCloseable {
        private val onScreen = ThreadLocal.withInitial { false }
        private val screen = Executors.newSingleThreadExecutor { work -> Thread({ onScreen.set(true); work.run() }, "caption screen") }
        private val historyWaits = CountDownLatch(1)
        private var history: Thread? = null
        private var historyFailure: Throwable? = null

        fun mainThread(step: () -> Unit) {
            if (onScreen.get()) return step()
            historyWaits.countDown()
            screen.submit(Runnable { step() }).get()
        }

        fun <T> onMain(work: () -> T): T = try {
            screen.submit(Callable { work() }).get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }

        fun fromHistory(work: () -> Unit) {
            history = thread(name = "history") {
                try {
                    work()
                } catch (failure: Throwable) {
                    historyFailure = failure
                } finally {
                    historyWaits.countDown()
                }
            }
            assertTrue(historyWaits.await(10, TimeUnit.SECONDS), "History's delete neither finished nor asked the main thread")
        }

        fun historyDone() {
            val asked = assertNotNull(history, "History was never asked")
            asked.join(10_000)
            assertFalse(asked.isAlive, "History's delete never finished")
            historyFailure?.let { throw it }
        }

        override fun close() {
            screen.shutdownNow()
        }
    }

    private fun savingOn(threads: Threads) = ConversationSaver(
        writer = writer, lines = { lines.toList() }, isListening = { listening }, settings = { settings }, transcribing = { settings },
        speakerName = { null }, inputName = { null }, startNewConversation = {}, setSaveHistory = { settings.saveHistory = it },
        scope = scope, now = { clock }, io = EmptyCoroutineContext, onMainThread = threads::mainThread,
    )

    /** Captions running all day: the conversations a long quiet closed this morning and this afternoon, their lines still on screen. */
    private fun closedToday(history: ConversationSaver, afternoon: Boolean): List<UUID> {
        listening = true
        history.listeningChanged()
        val closed = mutableListOf<UUID>()
        for ((text, at) in listOf("Good morning" to clock - 60 * 60, "Good afternoon" to clock - 30 * 60).take(if (afternoon) 2 else 1)) {
            say(text, at = at)
            closed += history.currentId
            assertTrue(history.checkForConversationBreak())
        }
        say("Good evening")
        return closed
    }

    /** History deletes as the caption screen reads, at a word, the conversations a long quiet closed, in the middle of reading them. */
    private fun deleteWhileTheScreenReads(threads: Threads, history: ConversationSaver, delete: () -> Unit) {
        var asked = false
        history.whileReadingClosed = {
            if (!asked) {
                asked = true
                threads.fromHistory(delete)
            }
        }
        threads.onMain { history.savedConversation(0) }
        threads.historyDone()
        history.whileReadingClosed = null
    }

    // History deletes on its own thread while the caption screen reads, at
    // every word on the main thread, the conversations a long quiet closed
    // to know which one each line belongs to. A delete landing as it read
    // them threw on the main thread and closed the app, so the captions and
    // sound alerts stopped with it.
    @Test
    fun `a conversation deleted in History while the caption screen reads the closed ones does not stop the captions`() {
        Threads().use { threads ->
            val history = savingOn(threads)
            val (morning, afternoon) = closedToday(history, afternoon = true)

            deleteWhileTheScreenReads(threads, history) { history.delete(morning) }
            assertEquals(listOf(null, afternoon), threads.onMain { (0..1).map(history::savedConversation) })
            assertEquals(listOf("Good afternoon"), saved().map { it.preview })
        }
    }

    @Test
    fun `deleting all in History while the caption screen reads the closed conversations does not stop the captions`() {
        Threads().use { threads ->
            val history = savingOn(threads)
            closedToday(history, afternoon = true)

            deleteWhileTheScreenReads(threads, history) { history.deleteAll() }
            assertEquals(listOf(null, null, null), threads.onMain { (0..2).map(history::savedConversation) })
            assertTrue(saved().isEmpty())
        }
    }

    // A table read from the closed conversations just before History's
    // delete and kept just after it went on naming the deleted conversation
    // for its lines until the next one closed: holding one of them opened
    // nothing, and a marked problem named a conversation that was gone.
    @Test
    fun `lines of a conversation deleted in History while the caption screen read them belong to no conversation afterwards`() {
        Threads().use { threads ->
            val history = savingOn(threads)
            val (morning) = closedToday(history, afternoon = false)

            deleteWhileTheScreenReads(threads, history) { history.delete(morning) }
            assertNull(threads.onMain { history.conversationOfLine(0) })
            assertTrue(saved().isEmpty())
        }
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
