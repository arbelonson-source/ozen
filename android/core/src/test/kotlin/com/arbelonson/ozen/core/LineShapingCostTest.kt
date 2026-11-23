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

    private companion object {
        const val SHORT_LINE = 6
        const val LONG_LINE = 100
        const val TIMES = 50
        const val HEBREW_BYTES_PER_WORD_ALLOWED = 100L
        const val ENGLISH_BYTES_PER_WORD_ALLOWED = 150L
        const val AGAIN_BYTES_ALLOWED = 64L
        const val ASKING_BYTES_ALLOWED = 16L
    }
}
