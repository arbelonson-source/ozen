package com.arbelonson.ozen.core

import java.lang.management.ManagementFactory
import java.text.BreakIterator
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeywordAlertMatcherCostTest {
    private val threadMemory = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun bytesPerLine(matcher: KeywordAlertMatcher, lines: List<String>, live: Boolean = false): Long {
        val before = threadMemory.currentThreadAllocatedBytes
        for (line in lines) if (live) matcher.matchesInLiveText(line) else matcher.matches(line)
        return (threadMemory.currentThreadAllocatedBytes - before) / lines.size
    }

    @Test
    fun `each saved name adds little to a word never read before`() {
        val names = listOf("נועה", "סבתא", "יונתן", "מיכאל")
        val one = KeywordAlertMatcher(listOf(KeywordAlert(phrase = names[0])), "he")
        val four = KeywordAlertMatcher(names.map { KeywordAlert(phrase = it) }, "he")
        val lines = (1..400).map { "שלום מילה$it" }
        bytesPerLine(one, lines.take(100))
        bytesPerLine(four, lines.take(100))
        val withOne = bytesPerLine(one, lines.drop(100))
        val withFour = bytesPerLine(four, lines.drop(100))
        val perName = (withFour - withOne) / 3
        assertTrue(perName < BYTES_PER_NAME_ALLOWED, "each saved name made a word never read before allocate $perName bytes more")
    }

    @Test
    fun `two words said apart are compared with a name written as one without starting a character splitter`() {
        val pairs = (1..400).map { "שלום" + "מילה$it" } + (1..400).map { "סבתא" + "word$it" }
        pairs.take(100).forEach { HebrewText.foldingFinalLetters(it) }
        val before = threadMemory.currentThreadAllocatedBytes
        pairs.forEach { HebrewText.foldingFinalLetters(it) }
        val each = (threadMemory.currentThreadAllocatedBytes - before) / pairs.size
        assertTrue(each < FOLDING_BYTES_ALLOWED, "folding the final letters of a new pair of words allocated $each bytes")
    }

    @Test
    fun `a live line with a direction mark costs a word about what a short one does`() {
        val matcher = KeywordAlertMatcher(listOf(KeywordAlert(phrase = "נועה")), "he")
        fun line(words: Int) = "\u200F" + (1..words).joinToString(" ") { "מילה$it" } + " הטל..."
        val long = List(60) { line(100) }
        val short = List(60) { line(6) }
        bytesPerLine(matcher, long, live = true)
        val longCost = bytesPerLine(matcher, long, live = true)
        bytesPerLine(matcher, short, live = true)
        val shortCost = bytesPerLine(matcher, short, live = true)
        assertTrue(longCost - shortCost < 8 * 1_024L, "a live line of 100 words with a direction mark allocated $longCost bytes, against $shortCost at 6")
    }

    private fun graphemeCount(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    private fun arabicStemBefore(stem: String) =
        stem.codePointCount(0, stem.length) >= 3 && stem.codePoints().allMatch { it in 0x0620..0x064A || it in 0x0671..0x06D3 }

    private fun gluedBefore(word: String, stem: String): Boolean {
        if (word == stem) return true
        if (HebrewText.everydayWordsNotNames[stem]?.contains(word) == true) return false
        if (HebrewText.attachedPrefixes.any { word == it + stem }) return true
        if (arabicStemBefore(stem)) {
            if (HebrewText.arabicAttachedPrefixes.any { word == it + stem }) return true
            if (stem !in HebrewText.arabicNamesThatAreWords && HebrewText.arabicArticlePrefixes.any { word == it + stem }) return true
            if (stem.startsWith("ال") && arabicStemBefore(stem.substring(2)) && listOf("لل", "ولل", "فلل").any { word == it + stem.substring(2) }) return true
        }
        if (graphemeCount(stem) < 3 || !stem.codePoints().allMatch { it in 0x1200..0x137F || it in 0x2D80..0x2DDF || it in 0xAB00..0xAB2F }) return false
        return (HebrewText.amharicAttachedPrefixes + "").any { prefix ->
            (HebrewText.amharicAttachedSuffixes + "").any { suffix -> (prefix + suffix).isNotEmpty() && word == prefix + stem + suffix }
        }
    }

    @Test
    fun `a word glued to a prefix is found as when the prefix was glued on first`() {
        val stems = listOf("נועה", "לי", "שרה", "דר", "גיל", "محمد", "الطبيب", "طبيب", "علي", "الياس", "نور", "አያት", "ሐኪም", "ሰላም", "ab", "Dana", "")
        val prefixes = HebrewText.attachedPrefixes + HebrewText.arabicAttachedPrefixes + HebrewText.arabicArticlePrefixes +
            HebrewText.amharicAttachedPrefixes + listOf("لل", "ولل", "فلل", "", "x", "וו")
        val random = Random(5)
        var glued = 0
        for (stem in stems) {
            val words = prefixes.flatMap { prefix ->
                listOf(prefix + stem, prefix + stem.drop(1), prefix + stem + "ן", prefix + stem.drop(2)) +
                    (HebrewText.amharicAttachedSuffixes + "").map { prefix + stem + it }
            } + HebrewText.everydayWordsNotNames.values.flatten() + List(50) { stems.random(random) + stems.random(random) }
            for (word in words) {
                val before = gluedBefore(word, stem)
                if (before) glued++
                assertEquals(before, HebrewText.stripAttachedPrefix(word, stem), "\"$word\" on \"$stem\"")
            }
        }
        assertTrue(glued > 200)
    }

    private companion object {
        const val BYTES_PER_NAME_ALLOWED = 3_072L
        const val FOLDING_BYTES_ALLOWED = 256L
    }
}
