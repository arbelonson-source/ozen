package com.arbelonson.ozen

import com.arbelonson.ozen.core.ChunkedList
import com.arbelonson.ozen.core.EmbeddingClusterer
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.KeywordMatch
import com.arbelonson.ozen.core.LockScreenCaptionLine
import com.arbelonson.ozen.core.LockScreenTextSize
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptSegment
import java.lang.management.ManagementFactory
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val threadMemory = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

private val alert = UUID(9, 9)

private fun id(number: Int) = UUID(1, number.toLong())

private fun heard(number: Int, line: UUID) =
    KeywordHit(id = UUID(4, number.toLong()), segmentID = line, match = KeywordMatch(alert, "סבתא", "סבתא", 0), timestamp = number.toDouble())

/** Line [number] of a long talk: three people in turns of three lines, unnamed as at first, and a quiet stretch every fifty lines. */
private fun said(number: Int, text: String = "line $number was said here", final: Boolean = true, voice: Int? = (number / 3) % 3) =
    TranscriptSegment(
        id = id(number), text = text, isCommitted = final, speakerClusterID = voice,
        startTimestamp = 1_000.0 + number * 3 + (number / 50) * 400, lastUpdateTimestamp = 1_002.0 + number * 3 + (number / 50) * 400,
    )

/**
 * What the app has besides the lines: a few of them starred, words from
 * the important words list heard all through, the saved conversation, and
 * the names, numbered as the pipeline numbers people it can't name yet.
 */
private class World(count: Int) {
    var lines: ChunkedList<TranscriptSegment> = ChunkedList.of((0 until count).map { said(it) })
    var next = count
    val starred = (0 until count step 7).map(::id).toMutableSet()
    var hits = (0 until 10).map { heard(it, id(it * count / 10)) }
    var hitLines = hits.map { it.segmentID }.toSet()
    val names = mutableMapOf<Int, String>()
    var saving = true
    var language = "he"
    val conversation = UUID(2, 2)
}

private fun show(world: World, lines: List<TranscriptSegment>, now: Double) = CaptionState.show(
    PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = lines,
    starred = { it in world.starred },
    conversation = { if (world.saving) world.conversation else null },
    keywordHits = world.hitLines,
    recentlyHeard = world.hits,
    languageCode = world.language,
    speakerName = { world.names[it.speakerClusterID!!] ?: EmbeddingClusterer.genericName(it.speakerClusterID!! + 1) },
    now = now,
)

private fun lockScreen(): List<LockScreenCaptionLine> = CaptionState.lockScreenLines(2, LockScreenTextSize.Regular, namesShown = true, languageCode = "he")

/**
 * Words said after the lines there: lines of [lineWords] words, each line
 * growing a word at a time as the engine sends it, as the pipeline's lists
 * come; [word] gives a line's words in order.
 */
private fun wordsAfter(world: World, count: Int, lineWords: Int = 10, word: (Int) -> String = { "word${it + 1}" }): List<ChunkedList<TranscriptSegment>> {
    var lines = world.lines
    return (0 until count).map { at ->
        val number = world.next + at / lineWords
        val words = at % lineWords + 1
        val line = said(number, text = (0 until words).joinToString(" ") { word(it) }, final = words == lineWords)
        lines = if (words == 1) lines.adding(line) else lines.replacing(lines.size - 1, line)
        lines
    }
}

/** A long line as people say it, in Hebrew: sentences, a time, pills to take, a phone number and a word in English. */
private val talk = (
    "אז אמרתי לו שנפגש מחר בשעה 10:30 ליד הבית של סבתא. הוא אמר שזה בסדר, ושאני אתקשר אליו ל־052-1234567 אם משהו משתנה. " +
        "צריך לקחת 3 כדורים פעמיים ביום אחרי האוכל, וחצי כוס מים. OK, נתראה ביום שלישי! אני לא בטוח שזה יעבוד אבל ננסה בכל זאת. " +
        "הרופא אמר שאפשר להגיע עד השעה ארבע וחצי, ושהתור הבא הוא רק בעוד שבועיים, אז כדאי לבקש תור מוקדם יותר."
    ).split(" ")

/**
 * The bytes the caption screen's state and the lock screen's lines
 * allocate on this thread for each word, counted by the JVM rather than
 * timed, after words that warm the same path up. The lists of lines are
 * made before counting starts, as the pipeline makes them.
 */
private fun bytesPerWord(
    lines: Int,
    lineWords: Int = 10,
    word: (Int) -> String = { "word${it + 1}" },
    warmUp: Int = 200,
    counted: Int = 100,
): Long {
    CaptionState.clear()
    val world = World(lines)
    show(world, world.lines, now = 1.0)
    CaptionState.screenLeft(1_000.0 + lines * 2)
    CaptionState.screenReturned(1_000.0 + lines * 2 + 600)
    val words = wordsAfter(world, warmUp + counted, lineWords, word)
    words.take(warmUp).forEach {
        show(world, it, now = 2.0)
        lockScreen()
    }
    val measured = words.drop(warmUp)
    val before = threadMemory.currentThreadAllocatedBytes
    measured.forEach {
        show(world, it, now = 2.0)
        lockScreen()
    }
    return (threadMemory.currentThreadAllocatedBytes - before) / measured.size
}

class CaptionStateLongConversationTest {
    @AfterTest
    fun clear() = CaptionState.clear()

    @Test
    fun `a word on the caption screen of a ten thousand line conversation costs about what it costs in a short one`() {
        assertTrue(threadMemory.isThreadAllocatedMemorySupported && threadMemory.isThreadAllocatedMemoryEnabled)
        bytesPerWord(10_000)
        val short = bytesPerWord(10)
        val long = bytesPerWord(10_000)
        assertTrue(
            long - short < EXTRA_BYTES_ALLOWED,
            "each word at 10,000 lines allocated $long bytes, against $short at 10 lines: ${long - short} more, " +
                "so lines that did not change are still being made again",
        )
    }

    @Test
    fun `asking which saved conversation each drawn line is in makes nothing new, however far into the conversation`() {
        assertTrue(threadMemory.isThreadAllocatedMemorySupported && threadMemory.isThreadAllocatedMemoryEnabled)
        bytesPerWord(10_000)
        val short = bytesPerWord(10)
        val long = bytesPerWord(10_000)
        assertTrue(
            long - short < LINE_NUMBER_BYTES_ALLOWED,
            "each word at 10,000 lines allocated $long bytes, against $short at 10 lines: ${long - short} more, " +
                "so each drawn line's number is still wrapped in an object to ask for its saved conversation",
        )
    }

    @Test
    fun `a word at the end of a long line costs about what it costs at the end of a short one`() {
        assertTrue(threadMemory.isThreadAllocatedMemorySupported && threadMemory.isThreadAllocatedMemoryEnabled)
        val spoken = { at: Int -> talk[at % talk.size] }
        bytesPerWord(200, lineWords = 80, word = spoken, warmUp = 160, counted = 160)
        val short = bytesPerWord(200, lineWords = 10, word = spoken, warmUp = 160, counted = 160)
        val long = bytesPerWord(200, lineWords = 80, word = spoken, warmUp = 160, counted = 160)
        assertTrue(
            long - short < LONG_LINE_EXTRA_BYTES_ALLOWED,
            "each word on lines of 80 words allocated $long bytes, against $short on lines of 10: ${long - short} more, " +
                "so the line being written costs more at every word the longer it gets",
        )
    }

    private fun run(plain: Boolean): List<Pair<CaptionScreenState, List<LockScreenCaptionLine>>> {
        CaptionState.clear()
        val resumedBefore = CaptionState.screen.value.speechResumed
        val world = World(260)
        var now = 0.0
        val seen = ArrayList<Pair<CaptionScreenState, List<LockScreenCaptionLine>>>()
        fun step(change: () -> Unit) {
            change()
            now += 1
            show(world, if (plain) ArrayList(world.lines) else world.lines, now)
            val screen = CaptionState.screen.value
            seen.add(screen.copy(speechResumed = screen.speechResumed - resumedBefore) to lockScreen())
        }
        fun add(vararg lines: TranscriptSegment) {
            for (line in lines) world.lines = world.lines.adding(line)
            world.next += lines.size
        }
        fun replace(index: Int, change: (TranscriptSegment) -> TranscriptSegment) {
            world.lines = world.lines.replacing(index, change(world.lines[index]))
        }
        step { }
        step { }
        for (words in 1..4) step { if (words == 1) add(said(world.next, "and", final = false)) else replace(world.lines.size - 1) { it.copy(text = it.text + " more") } }
        step { replace(world.lines.size - 1) { it.copy(isCommitted = true, uncertainWords = listOf("more")) } }
        step { add(said(world.next), said(world.next + 1, voice = null), said(world.next + 2, voice = 7)) }
        step { replace(200) { it.copy(text = "a fixed word at 050 123 4567") } }
        step { replace(10) { it.copy(text = "a fix before the screen's lines") } }
        step { replace(0) { it.copy(speakerClusterID = 2) } }
        step { replace(1) { it.copy(speakerClusterID = 5) } }
        step { world.names[1] = "Dana" }
        step { world.names[2] = "Speaker 2" }
        step { world.starred += world.lines[230].id }
        step { world.starred -= world.lines[231].id }
        step { world.saving = false }
        step { world.saving = true }
        step { world.hitLines = world.hitLines + world.lines[240].id }
        step {
            world.hits = world.hits + heard(50, world.lines[250].id) + heard(51, UUID(3, 3))
        }
        step { CaptionState.screenLeft(world.lines.last().lastUpdateTimestamp + 1) }
        repeat(3) { step { add(said(world.next)) } }
        step { CaptionState.screenReturned(world.lines.last().lastUpdateTimestamp + 100) }
        step { add(said(world.next)) }
        step { replace(world.lines.size - 3) { it.copy(text = "missed, then fixed") } }
        step { CaptionState.acknowledgeAwayLines() }
        step { world.lines = ChunkedList.of(world.lines.filterIndexed { index, _ -> index != 120 && index != world.lines.size - 3 }) }
        step { world.lines = ChunkedList.of(world.lines.filterIndexed { index, _ -> index !in 3..5 }) }
        step { CaptionState.readingBack(world.lines.size) }
        repeat(30) { step { add(said(world.next)) } }
        step { CaptionState.readingBack(null) }
        step { add(said(world.next)) }
        step { world.language = "en" }
        step { }
        step { world.lines = ChunkedList.of(world.lines.dropLast(2)) }
        step { add(said(world.next, "", voice = null)) }
        step { world.lines = ChunkedList.empty() }
        repeat(3) { step { add(said(world.next)) } }
        return seen
    }

    @Test
    fun `the pipeline's lists show exactly what a plain copy of the same lines shows, through words, fixes, names, stars and time away`() {
        val looked = run(plain = false)
        val plain = run(plain = true)
        assertEquals(plain.size, looked.size)
        for (step in plain.indices) assertEquals(plain[step], looked[step], "step $step")
    }

    private companion object {
        const val EXTRA_BYTES_ALLOWED = 8 * 1_024L
        const val LINE_NUMBER_BYTES_ALLOWED = 2 * 1_024L
        const val LONG_LINE_EXTRA_BYTES_ALLOWED = 32 * 1_024L
    }
}
