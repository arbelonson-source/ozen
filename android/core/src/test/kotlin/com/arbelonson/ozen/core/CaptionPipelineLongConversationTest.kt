package com.arbelonson.ozen.core

import java.lang.management.ManagementFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val threadMemory = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

private class Conversation(val pipeline: CaptionPipeline, val engine: FakeEngine)

private fun said(id: UUID, text: String, final: Boolean = false, doubtful: List<String> = emptyList()) =
    TranscriptToken(utteranceID = id, text = text, isFinal = final, timestamp = 1_000.0, uncertainWords = doubtful)

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.conversationOf(
    lines: Int,
    settings: AppSettings = AppSettings.default,
    say: (Int) -> String = { "line $it was said here" },
): Conversation {
    val engine = FakeEngine()
    val (pipeline, _, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine))
    pipeline.start(settings)
    repeat(lines) { engine.emit(said(UUID.randomUUID(), say(it), final = true)) }
    runCurrent()
    assertEquals(lines, pipeline.segments.size)
    return Conversation(pipeline, engine)
}

/**
 * The bytes the pipeline's own thread (the test's, here) allocates for
 * each token, counted by the JVM rather than timed, after a few tokens
 * that warm the same path up ([warmUp], or bring a line up to the length
 * being measured). The tokens are made before counting starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.bytesPerToken(conversation: Conversation, tokens: List<TranscriptToken>, warmUp: Int = 5): Long {
    tokens.take(warmUp).forEach {
        conversation.engine.emit(it)
        runCurrent()
    }
    val counted = tokens.drop(warmUp)
    val before = threadMemory.currentThreadAllocatedBytes
    counted.forEach {
        conversation.engine.emit(it)
        runCurrent()
    }
    return (threadMemory.currentThreadAllocatedBytes - before) / counted.size
}

/**
 * One line growing a word at a time, as the engine sends a sentence being
 * said: every pass carries all its words so far, as [pass] writes them, and
 * the words [doubtful] says the engine was unsure of on that pass.
 */
private fun growingLine(
    words: Int,
    word: (Int) -> String = { "word$it" },
    pass: (String) -> String = { it },
    doubtful: (String) -> List<String> = { emptyList() },
): List<TranscriptToken> {
    val id = UUID.randomUUID()
    return (1..words).map { count -> pass((1..count).joinToString(" ") { word(it) }).let { said(id, it, doubtful = doubtful(it)) } }
}

/** Hebrew captions with a name to be alerted about and a word fix saved that writes that name for "נוע". */
private fun withNameAlertAndFix(): AppSettings = AppSettings.default.copy().also {
    it.keywordAlerts = listOf(KeywordAlert(phrase = "נועה"))
    it.wordFixes = listOf(WordFix("נוע", "נועה"))
}

/** Hebrew captions with [withNameAlertAndFix]'s alert and fix, and the words the engine was unsure of marked on the screen. */
private fun withNameAlertFixAndDoubtfulWords(): AppSettings = withNameAlertAndFix().also {
    it.display = it.display.copy(markUncertainLines = true)
}

/** A line of [word]s said with [settings], each pass written as [pass] writes it, with the words [doubtful] picks marked doubtful. */
private class LineCase(
    val setup: String,
    val settings: AppSettings,
    val pass: (String) -> String = { it },
    val doubtful: (String) -> List<String> = { emptyList() },
    val word: (Int) -> String,
)

private fun newLines(count: Int): List<TranscriptToken> =
    (1..count).map { said(UUID.randomUUID(), "and then $it more", final = true) }

class CaptionPipelineLongConversationTest {
    @Test
    fun `a word on the newest line of a ten thousand line conversation costs about what it costs in a short one`() = runTest {
        assertTrue(threadMemory.isThreadAllocatedMemorySupported && threadMemory.isThreadAllocatedMemoryEnabled)
        for (words in listOf(1, 100)) {
            val short = bytesPerToken(conversationOf(10), growingLine(words + 5))
            val long = bytesPerToken(conversationOf(10_000), growingLine(words + 5))
            assertTrue(
                long - short < EXTRA_BYTES_ALLOWED,
                "counting $words word(s), each word at 10,000 lines allocated $long bytes, against $short at 10 lines: " +
                    "${long - short} more, so the whole list of lines is still being copied",
            )
        }
    }

    @Test
    fun `a word on a line of a hundred words costs about what it costs on a line of six`() = runTest {
        val cases = listOf(
            LineCase("no alerts or fixes", AppSettings.default) { "word$it" },
            LineCase("a name alert and a word fix", withNameAlertAndFix()) { "מילה$it" },
            LineCase("a word fix whose heard word is on the line", withNameAlertAndFix()) { if (it == 2) "נוע" else "מילה$it" },
            LineCase("a name alert, on live passes cut mid-word after a direction mark", withNameAlertAndFix(), { "\u200F$it הטל..." }) { "מילה$it" },
            LineCase(
                "a word fix whose heard word is on the line, and doubtful words marked",
                withNameAlertFixAndDoubtfulWords(),
                doubtful = { line -> listOf("נוע", line.substringAfterLast(' ')) },
            ) { if (it == 2) "נוע" else "מילה$it" },
        )
        val failures = ArrayList<String>()
        for (case in cases) {
            val conversation = conversationOf(10, case.settings)
            // A first line says every word counted below, so both lines
            // count words seen before: a word never seen yet costs more, the
            // same on a line of any length.
            bytesPerToken(conversation, growingLine(LONG_LINE + 2 * COUNTED_WORDS, case.word, case.pass, case.doubtful))
            val short = bytesPerToken(conversation, growingLine(SHORT_LINE + COUNTED_WORDS, case.word, case.pass, case.doubtful), warmUp = SHORT_LINE)
            val long = bytesPerToken(conversation, growingLine(LONG_LINE + COUNTED_WORDS, case.word, case.pass, case.doubtful), warmUp = LONG_LINE)
            if (long - short >= EXTRA_BYTES_ALLOWED) {
                failures += "with ${case.setup}, counting $COUNTED_WORDS words, each word after $LONG_LINE allocated $long bytes, against $short " +
                    "after $SHORT_LINE: ${long - short} more, so some step still redoes its work over the whole line at every word"
            }
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun `a word costs about what it costs after ten lines when two thousand lines said a saved name`() = runTest {
        val settings = withNameAlertAndFix()
        val few = conversationOf(10, settings) { "נועה אמרה $it" }
        val many = conversationOf(2_000, settings) { "נועה אמרה $it" }
        assertEquals(2_000, many.pipeline.keywordHitSegmentIDs.size)
        val lines = listOf(
            "saying the name" to { index: Int -> if (index == 1) "נועה" else "מילה$index" },
            "not saying it" to { index: Int -> "מילה$index" },
        )
        val failures = ArrayList<String>()
        for ((line, word) in lines) {
            val short = bytesPerToken(few, growingLine(COUNTED_WORDS + 5, word))
            val long = bytesPerToken(many, growingLine(COUNTED_WORDS + 5, word))
            if (long - short >= EXTRA_BYTES_ALLOWED) {
                failures += "on a line $line, each word after 2,000 lines with the name allocated $long bytes, against $short after 10: " +
                    "${long - short} more, so the lines with an alert are still copied at every word"
            }
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
        assertEquals(2_001, many.pipeline.keywordHitSegmentIDs.size)
    }

    @Test
    fun `a new line in a ten thousand line conversation costs about what it costs in a short one`() = runTest {
        val short = bytesPerToken(conversationOf(10), newLines(105))
        val long = bytesPerToken(conversationOf(10_000), newLines(105))
        assertTrue(
            long - short < EXTRA_BYTES_ALLOWED,
            "counting 100 lines, each new line at 10,000 lines allocated $long bytes, against $short at 10 lines: ${long - short} more, " +
                "so the whole list of lines is still being copied",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `lines already read stay as they were read while more words, lines and fixes arrive`() = runTest {
        val conversation = conversationOf(300)
        val pipeline = conversation.pipeline
        val first = pipeline.segments.first().id
        val read = pipeline.segments
        val asRead = read.map { it.text }
        growingLine(20).forEach { conversation.engine.emit(it) }
        newLines(70).forEach { conversation.engine.emit(it) }
        runCurrent()
        assertEquals("line 0 was heard here", pipeline.correctWord(first, WordFix("said", "heard")))
        assertEquals(asRead, read.map { it.text })
        assertEquals(300, read.size)
        assertEquals(371, pipeline.segments.size)
        assertEquals("line 0 was heard here", pipeline.segments.first().text)
        assertEquals("line 0 was said here", read.first().text)
        assertNotEquals(read, pipeline.segments)
        assertEquals(asRead.drop(1), pipeline.segments.subList(1, 300).map { it.text })
    }

    private companion object {
        const val EXTRA_BYTES_ALLOWED = 8 * 1_024L
        const val SHORT_LINE = 6
        const val LONG_LINE = 100
        const val COUNTED_WORDS = 10
    }
}
