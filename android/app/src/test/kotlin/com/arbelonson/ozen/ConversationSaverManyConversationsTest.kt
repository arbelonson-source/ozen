package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope

private val threadClock = ManagementFactory.getThreadMXBean()

/**
 * A phone listening for days: [closedCount] conversations a long quiet
 * closed, whose 300 lines are all still on the screen, and the one being
 * captioned now.
 */
private class Evening(closedCount: Int) {
    val folder = Files.createTempDirectory("ozen-many-conversations").toFile()
    val lines = ArrayList<TranscriptSegment>()
    private var clock = 1_800_000_000.0
    private val settings = AppSettings.default
    val saver = ConversationSaver(
        writer = TranscriptHistoryWriter(TranscriptHistoryStore(folder)),
        lines = { lines },
        isListening = { true },
        settings = { settings },
        transcribing = { settings },
        speakerName = { null },
        inputName = { null },
        startNewConversation = {},
        setSaveHistory = { settings.saveHistory = it },
        scope = TestScope(),
        now = { clock },
        io = EmptyCoroutineContext,
    )
    val closed = ArrayList<UUID>()

    init {
        saver.listeningChanged()
        repeat(closedCount) {
            repeat(300 / closedCount) { say() }
            closed += saver.currentId
            clock += 30 * 60
            check(saver.checkForConversationBreak())
        }
        say()
    }

    private fun say() {
        lines += TranscriptSegment(id = UUID.randomUUID(), text = "line ${lines.size}", isCommitted = true, startTimestamp = clock, lastUpdateTimestamp = clock)
    }

    /** What the caption screen asks at every word: the saved conversation of each line it draws. */
    fun word(): Int {
        var found = 0
        for (index in lines.size - 301 until lines.size) if (saver.savedConversation(index) != null) found++
        return found
    }

    /** The CPU time this thread spends on a word, the least of a few tries, after words that warm the same path up. */
    fun nanosPerWord(): Long {
        repeat(2_000) { word() }
        return (1..5).minOf {
            val before = threadClock.currentThreadCpuTime
            repeat(200) { word() }
            (threadClock.currentThreadCpuTime - before) / 200
        }
    }
}

class ConversationSaverManyConversationsTest {
    private val evenings = ArrayList<Evening>()

    @AfterTest
    fun removeFolders() {
        for (evening in evenings) evening.folder.deleteRecursively()
    }

    private fun evening(closedCount: Int) = Evening(closedCount).also { evenings += it }

    @Test
    fun `each drawn line's conversation is found about as fast among 300 closed conversations as among 3`() {
        assertTrue(threadClock.isCurrentThreadCpuTimeSupported)
        val few = evening(3)
        val many = evening(300)
        assertEquals(301, many.word())
        assertEquals(many.closed[0], many.saver.savedConversation(0))
        assertEquals(many.closed[299], many.saver.savedConversation(299))
        assertEquals(many.saver.currentId, many.saver.savedConversation(300))
        assertEquals(few.closed[1], few.saver.savedConversation(100))
        val fewNanos = few.nanosPerWord()
        val manyNanos = many.nanosPerWord()
        assertTrue(
            manyNanos < fewNanos * 4 + 10_000,
            "a word took $manyNanos ns of CPU among 300 closed conversations, against $fewNanos ns among 3: " +
                "each line's conversation is still looked for among all of them",
        )
    }

    @Test
    fun `the conversation found for a line is the latest closed one that holds it, as when each was asked in turn`() {
        val random = Random(724)
        repeat(500) {
            // Lines dropped from the screen between two closes make their stretches overlap, or leave one empty.
            val conversations = (0 until random.nextInt(0, 30)).map {
                val start = random.nextInt(0, 60)
                UUID.randomUUID() to (start until start + random.nextInt(0, 25))
            }
            val lines = ClosedLines(conversations)
            val order = (-2..90).toList().let { if (random.nextBoolean()) it else it.shuffled(random) }
            for (index in order) {
                assertEquals(conversations.lastOrNull { index in it.second }?.first, lines.at(index), "line $index of $conversations")
            }
        }
    }
}
