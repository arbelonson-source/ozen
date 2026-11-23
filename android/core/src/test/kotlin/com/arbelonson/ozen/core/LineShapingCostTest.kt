package com.arbelonson.ozen.core

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val threadMemory = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

private val hebrew = (
    "אז אמרתי לו שנפגש מחר ליד הבית של סבתא. הוא אמר שזה בסדר, ושאני אתקשר אליו אם משהו משתנה. " +
        "אני לא בטוח שזה יעבוד אבל ננסה בכל זאת. הרופא אמר שאפשר להגיע מחר בבוקר, ושהתור הבא הוא רק בעוד חודש, אז כדאי לבקש תור מוקדם יותר."
    ).split(" ")

private val english =
    "So I told him we would meet tomorrow near the house. He said that is fine, and that I will call him if something changes. I am not sure it will work but we will try anyway."
        .split(" ")

private fun line(words: List<String>, count: Int) = (0 until count).joinToString(" ") { words[it % words.size] }

private inline fun bytesEach(count: Int, block: () -> Unit): Long {
    block()
    val before = threadMemory.currentThreadAllocatedBytes
    block()
    return (threadMemory.currentThreadAllocatedBytes - before) / count
}

class LineShapingCostTest {
    @Test
    fun `finding the numbers on a line costs little for each word on it with no number`() {
        assertTrue(threadMemory.isThreadAllocatedMemorySupported && threadMemory.isThreadAllocatedMemoryEnabled)
        val failures = ArrayList<String>()
        val languages = listOf(Triple("he", hebrew, HEBREW_BYTES_PER_WORD_ALLOWED), Triple("en", english, ENGLISH_BYTES_PER_WORD_ALLOWED))
        for ((language, words, allowed) in languages) {
            val short = line(words, SHORT_LINE)
            val long = line(words, LONG_LINE)
            val shortCost = bytesEach(TIMES) { repeat(TIMES) { NumberEmphasis.ranges(short, language) } }
            val longCost = bytesEach(TIMES) { repeat(TIMES) { NumberEmphasis.ranges(long, language) } }
            val perWord = (longCost - shortCost) / (LONG_LINE - SHORT_LINE)
            if (perWord >= allowed) {
                failures += "in \"$language\", finding the numbers allocated $perWord bytes for each word of the line, $allowed allowed: " +
                    "$longCost bytes on a line of $LONG_LINE words, against $shortCost on one of $SHORT_LINE"
            }
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun `a line searched for phone numbers again, as it is for how it is drawn, copied and dialled, costs no more`() {
        val growing = (1..LONG_LINE).map { line(hebrew, it) }
        val once = bytesEach(growing.size) { for (text in growing) PhoneNumbers.matches(text) }
        val thrice = bytesEach(growing.size) {
            for (text in growing) {
                PhoneNumbers.matches(text)
                PhoneNumbers.matches(text)
                PhoneNumbers.matches(text)
            }
        }
        assertTrue(
            thrice - once < AGAIN_BYTES_ALLOWED,
            "searching each word's line three times allocated $thrice bytes a word, against $once searching it once",
        )
    }

    @Test
    fun `asking which way a language reads makes nothing new`() {
        val codes = UILanguage.entries.map { it.languageCode }
        val each = bytesEach(codes.size * TIMES) { repeat(TIMES) { for (index in codes.indices) TextDirection.isRightToLeft(codes[index]) } }
        assertTrue(each < ASKING_BYTES_ALLOWED, "asking whether a language reads from the right allocated $each bytes each time")
    }

    /** What [shape] allocates for each word more that [long] has than [short], [long] having [LONG_LINE] words and [short] [SHORT_LINE]. */
    private fun bytesPerWord(short: String, long: String, shape: (String) -> Unit): Long {
        val shortCost = bytesEach(TIMES) { repeat(TIMES) { shape(short) } }
        val longCost = bytesEach(TIMES) { repeat(TIMES) { shape(long) } }
        return (longCost - shortCost) / (LONG_LINE - SHORT_LINE)
    }

    @Test
    fun `breaking a growing line into paragraphs costs little for each word on it`() {
        val failures = ArrayList<String>()
        val languages = listOf(Triple("he", hebrew, HEBREW_PARAGRAPH_BYTES_PER_WORD_ALLOWED), Triple("en", english, ENGLISH_PARAGRAPH_BYTES_PER_WORD_ALLOWED))
        for ((language, words, allowed) in languages) {
            val perWord = bytesPerWord(line(words, SHORT_LINE), line(words, LONG_LINE)) { CaptionLayout.readableText(it) }
            if (perWord >= allowed) failures += "in \"$language\", breaking the line into paragraphs allocated $perWord bytes for each word of it, $allowed allowed"
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun `marking each paragraph of a growing line to read from the right costs little for each word on it`() {
        val short = CaptionLayout.readableText(line(hebrew, SHORT_LINE))
        val long = CaptionLayout.readableText(line(hebrew, LONG_LINE))
        assertTrue(long.count { it == '\n' } >= 4)
        val perWord = bytesPerWord(short, long) { CaptionLayout.directed(it, "he") }
        assertTrue(perWord < MARKING_BYTES_PER_WORD_ALLOWED, "marking the paragraphs allocated $perWord bytes for each word of the line, $MARKING_BYTES_PER_WORD_ALLOWED allowed")
    }

    @Test
    fun `a line with no phone number, star code or dose in it is drawn and copied as it is, with nothing made`() {
        val said = line(hebrew, LONG_LINE)
        val joining = bytesEach(TIMES) { repeat(TIMES) { CaptionLayout.joiningNumbers(said, "he") } }
        val findingNumbers = bytesEach(TIMES) { repeat(TIMES) { NumberEmphasis.ranges(said, "he") } }
        val isolating = bytesEach(TIMES) { repeat(TIMES) { CaptionLayout.isolatingNumbers(said) } }
        assertTrue(
            joining - findingNumbers < NOTHING_TO_CHANGE_BYTES_ALLOWED && isolating < NOTHING_TO_CHANGE_BYTES_ALLOWED,
            "a line with nothing to join allocated $joining bytes to join, $findingNumbers of them finding its numbers, " +
                "and $isolating to isolate its phone numbers",
        )
    }

    @Test
    fun `a phone number just said at the end of a growing line is isolated in one copy of the line`() {
        val number = " 050-1234567"
        val short = line(hebrew, SHORT_LINE) + number
        val long = line(hebrew, LONG_LINE) + number
        assertTrue(CaptionLayout.isolatingNumbers(long).endsWith("\u2066050-1234567\u2069"))
        val perWord = bytesPerWord(short, long) { CaptionLayout.isolatingNumbers(it) }
        assertTrue(perWord < ISOLATING_BYTES_PER_WORD_ALLOWED, "isolating the phone number allocated $perWord bytes for each word of the line, $ISOLATING_BYTES_PER_WORD_ALLOWED allowed")
    }

    @Test
    fun `asking whether a line has a right-to-left letter makes nothing new`() {
        val said = line(english, LONG_LINE)
        val each = bytesEach(TIMES) { repeat(TIMES) { TextDirection.containsRightToLeftLetter(said) } }
        assertTrue(each < ASKING_BYTES_ALLOWED, "asking whether a line has a right-to-left letter allocated $each bytes each time")
    }

    private companion object {
        const val SHORT_LINE = 6
        const val LONG_LINE = 100
        const val TIMES = 50
        const val HEBREW_BYTES_PER_WORD_ALLOWED = 100L
        const val ENGLISH_BYTES_PER_WORD_ALLOWED = 150L
        const val AGAIN_BYTES_ALLOWED = 64L
        const val ASKING_BYTES_ALLOWED = 16L
        const val HEBREW_PARAGRAPH_BYTES_PER_WORD_ALLOWED = 40L
        const val ENGLISH_PARAGRAPH_BYTES_PER_WORD_ALLOWED = 20L
        const val MARKING_BYTES_PER_WORD_ALLOWED = 40L
        const val NOTHING_TO_CHANGE_BYTES_ALLOWED = 64L
        const val ISOLATING_BYTES_PER_WORD_ALLOWED = 32L
    }
}
