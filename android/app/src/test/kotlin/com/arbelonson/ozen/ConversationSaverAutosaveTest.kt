package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.SerialQueue
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

class ConversationSaverAutosaveTest {
    private val folder = Files.createTempDirectory("ozen-autosave").toFile()
    private val store = TranscriptHistoryStore(folder)
    private val queue = SerialQueue("ozen.autosave-test")
    private val writer = TranscriptHistoryWriter(store, queue)
    private val scope = TestScope()
    private val lines = ArrayList<TranscriptSegment>()
    private val settings = AppSettings.default
    private val clock = 1_800_000_000.0
    private val caller = Thread.currentThread()
    private var namedOnCaller = 0

    private val saver = ConversationSaver(
        writer = writer,
        lines = { lines },
        isListening = { true },
        settings = { settings },
        transcribing = { settings },
        speakerName = {
            if (Thread.currentThread() === caller) namedOnCaller += 1
            it.speakerClusterID?.let { voice -> "Speaker ${voice + 1}" }
        },
        inputName = { null },
        startNewConversation = {},
        setSaveHistory = {},
        scope = scope,
        now = { clock },
        io = EmptyCoroutineContext,
    )

    @AfterTest
    fun finish() {
        queue.resume()
        writer.waitUntilIdle()
        folder.deleteRecursively()
    }

    private fun say(count: Int) = repeat(count) {
        val at = clock - 60 + lines.size * 0.001
        lines += TranscriptSegment(
            id = UUID.randomUUID(), text = "Take three pills every morning, line ${lines.size}", isCommitted = true,
            speakerClusterID = lines.size % 3, startTimestamp = at, lastUpdateTimestamp = at,
        )
    }

    private fun twentySeconds() {
        scope.advanceTimeBy(20_001)
        scope.runCurrent()
    }

    @Test
    fun `the autosave every twenty seconds makes the conversation on the writer's thread, not on the caption screen's`() {
        say(600)
        saver.listeningChanged()
        twentySeconds()
        writer.waitUntilIdle()
        assertEquals(0, namedOnCaller, "lines named for the autosave on the caption screen's thread")
        assertEquals(600, store.listSummaries().single().segmentCount)
    }

    @Test
    fun `an autosave keeps the lines as they were when it was asked for, though more come before it is written`() {
        say(5)
        saver.listeningChanged()
        queue.suspend()
        twentySeconds()
        say(3)
        queue.resume()
        writer.waitUntilIdle()
        val record = store.load(store.listSummaries().single().id)!!
        assertEquals(lines.take(5).map { it.id }, record.segments.map { it.id })
        assertEquals(listOf("Speaker 1", "Speaker 2", "Speaker 3", "Speaker 1", "Speaker 2"), record.segments.map { it.speakerName })
    }

    @Test
    fun `an autosave writes each voice's name as it was when it was asked for, though the voice is named before it is written`() {
        val names = mutableMapOf(0 to "Speaker 1")
        var askedOnCaller = 0
        val named = ConversationSaver(
            writer = writer, lines = { lines }, isListening = { true }, settings = { settings }, transcribing = { settings },
            speakerName = { names[it.speakerClusterID] }, inputName = { null }, startNewConversation = {}, setSaveHistory = {},
            scope = scope, now = { clock }, io = EmptyCoroutineContext,
            speakerNames = {
                if (Thread.currentThread() === caller) askedOnCaller += 1
                val now = names.toMap()
                val lookup: (TranscriptSegment) -> String? = { now[it.speakerClusterID] }
                lookup
            },
        )
        say(1)
        named.listeningChanged()
        queue.suspend()
        twentySeconds()
        names[0] = "Dana"
        queue.resume()
        writer.waitUntilIdle()
        assertEquals(1, askedOnCaller)
        assertEquals(listOf("Speaker 1"), store.load(store.listSummaries().single().id)!!.segments.map { it.speakerName })
    }
}
