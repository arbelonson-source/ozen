package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordFixesReviewTest {
    private fun fix(heard: String, written: String) = WordFix(heard, written)

    @Test
    fun `correcting a word an earlier fix wrote also moves that earlier fix`() {
        val list = WordFixes.adding(fix("Avi", "Avy"), listOf(fix("aval", "Avi"), fix("x", "y")))
        assertEquals(listOf(fix("Avi", "Avy"), fix("aval", "Avy"), fix("x", "y")), list)
        assertEquals(listOf(fix("q", "r"), fix("aval", "Avi")), WordFixes.adding(fix("q", "r"), listOf(fix("aval", "Avi"))))
    }

    @Test
    fun `a phrase with punctuation inside it is refused, one with punctuation at its ends is kept`() {
        assertTrue(WordFixes.normalized(listOf(fix("St. Mary", "Saint Mary"))).isEmpty())
        assertTrue(WordFixes.normalized(listOf(fix("bat, sheva", "Batsheva"))).isEmpty())
        assertEquals(1, WordFixes.normalized(listOf(fix("bat sheva", "Batsheva"))).size)
        assertEquals(1, WordFixes.normalized(listOf(fix("bat sheva.", "Batsheva"))).size)
        assertEquals(1, WordFixes.normalized(listOf(fix("St.", "Saint"))).size)
    }

    @Test
    fun `Chinese words are fixed inside an unspaced sentence, once, without chaining`() {
        val list = listOf(fix("奶奶", "外婆"), fix("外婆", "爷爷"))
        assertEquals("外婆来了", WordFixes.apply(list, "奶奶来了"))
        assertEquals("我们去看外婆吧，外婆好", WordFixes.apply(list, "我们去看奶奶吧，奶奶好"))
        assertEquals("爷爷来了", WordFixes.apply(list, "爷爷来了"))
        assertEquals("doctor 大夫 ok", WordFixes.apply(listOf(fix("医生", "大夫")), "doctor 医生 ok"))
    }

    @Test
    fun `a case-only fix stays dropped`() {
        assertTrue(WordFixes.normalized(listOf(fix("avi", "Avi"))).isEmpty())
    }

    @Test
    fun `a Hebrew sof pasuq and maqaf are not word marks, so a fix still matches around them`() {
        assertEquals("רותי\u05C3", WordFixes.apply(listOf(fix("רותם", "רותי")), "רותם\u05C3"))
        assertEquals("תל\u05BEאבי", WordFixes.apply(listOf(fix("אביב", "אבי")), "תל\u05BEאביב"))
        assertEquals("רותי", WordFixes.apply(listOf(fix("רותם", "רותי")), "רו\u05B6תם"))
    }

    @Test
    fun `a Devanagari word with vowel signs is one word, so its fix is kept and applied`() {
        val list = listOf(fix("दादी", "नानी"))
        assertEquals(list, WordFixes.normalized(list))
        assertEquals("मेरी नानी आई", WordFixes.apply(list, "मेरी दादी आई"))
        assertEquals(listOf("दादी", "आई"), WordFixes.wordsOf("दादी आई"))
    }

    @Test
    fun `a Han character outside the basic plane is read as one character`() {
        val rare = "\uD842\uDFB7"
        assertEquals(1, WordFixes.normalized(listOf(fix(rare, "吉"))).size)
        assertEquals("吉野家", WordFixes.apply(listOf(fix(rare + "野家", "吉野家")), rare + "野家"))
        assertEquals("吉野家好", WordFixes.apply(listOf(fix(rare, "吉")), rare + "野家好"))
        assertEquals(listOf(rare), WordFixes.wordsOf(rare))
    }

    @Test
    fun `at the limit a fix may replace one with the same heard word but not add another`() {
        val full = (1..WordFixes.MAXIMUM_FIXES).map { fix("heard$it", "written$it") }
        assertTrue(WordFixes.canAdd(fix("Heard5", "other"), full))
        assertTrue(!WordFixes.canAdd(fix("fresh", "other"), full))
        assertTrue(WordFixes.canAdd(fix("fresh", "other"), full.drop(1)))
        assertTrue(!WordFixes.canAdd(fix("", "other"), full.drop(1)))
        val replaced = WordFixes.adding(fix("heard5", "other"), full)
        assertEquals(WordFixes.MAXIMUM_FIXES, replaced.size)
        assertEquals("other", replaced.first().written)
    }

    @Test
    fun `the arrow of a fix points from what was heard to what is written in either reading direction`() {
        assertEquals("\u2192", WordFixes.arrow("aval"))
        assertEquals("\u2190", WordFixes.arrow("רותם"))
        assertEquals("\u2190", WordFixes.arrow("1 רותם"))
        assertEquals("\u2190", WordFixes.arrow("\u0627\u0644"))
        assertEquals("\u2192", WordFixes.arrow("123"))
    }

    @Test
    fun `doubtful words follow a phrase fix, each to its own word`() {
        val list = listOf(fix("New York", "NYC"))
        val text = "I love New York and York"
        assertEquals(listOf("nyc"), WordFixes.applyToUncertain(list, text, listOf("York")))
        assertEquals(listOf("nyc"), WordFixes.applyToUncertain(list, text, listOf("New", "York")))
        assertEquals(listOf("nyc", "York"), WordFixes.applyToUncertain(list, text, listOf("York", "York")))
        assertEquals(listOf("love"), WordFixes.applyToUncertain(list, text, listOf("love")))
        assertEquals(listOf("והרותי"), WordFixes.applyToUncertain(listOf(fix("רותם", "רותי")), "וגם והרותם", listOf("והרותם")))
    }

    @Test
    fun `a doubtful word stays marked after a fix to capitals or after a prefix and a maqaf`() {
        fun marked(fixes: List<WordFix>, heard: String, doubtful: List<String>): List<String> {
            val shown = WordFixes.apply(fixes, heard)
            return UncertainWords.ranges(shown, WordFixes.applyToUncertain(fixes, heard, doubtful)).map { shown.substring(it.start, it.endExclusive) }
        }
        val phone = listOf(fix("אייפון", "iPhone"))
        assertEquals(listOf("ב\u05BEiPhone"), marked(phone, "קניתי באייפון חדש", listOf("באייפון")))
        assertEquals(listOf("iPhone"), marked(phone, "אייפון חדש", listOf("אייפון")))
        assertEquals(listOf("NYC"), marked(listOf(fix("New York", "NYC")), "I love New York", listOf("york")))
        assertEquals(listOf("~iphone", "חדש"), WordFixes.applyToUncertain(phone, "שום דבר", listOf("~אייפון", "חדש")))
    }

    @Test
    fun `a sure copy keeps the doubt on its own copy`() {
        val nyc = listOf(fix("New York", "NYC"))
        val line = "I love New York and York"
        val shown = WordFixes.apply(nyc, line)
        fun marked(entries: List<String>) =
            UncertainWords.ranges(shown, WordFixes.applyToUncertain(nyc, line, entries)).map { shown.substring(it.start, it.endExclusive) }
        assertEquals(listOf("York"), marked(listOf("~york", "york")))
        assertEquals(listOf("NYC"), marked(listOf("york", "~york")))
        val lala = listOf(fix("la la", "lala"))
        assertEquals(1, UncertainWords.ranges("lala", WordFixes.applyToUncertain(lala, "la la", listOf("~la", "la"))).size)
    }

    @Test
    fun `a fix longer than the limit cannot be added, as on the iPhone`() {
        val longest = "a".repeat(WordFixes.MAXIMUM_LENGTH)
        assertTrue(WordFixes.canAdd(fix(longest, "b".repeat(WordFixes.MAXIMUM_LENGTH)), emptyList()))
        assertTrue(WordFixes.canAdd(fix(" $longest ", "other"), emptyList()))
        assertTrue(!WordFixes.canAdd(fix(longest + "b", "other"), emptyList()))
        assertTrue(!WordFixes.canAdd(fix("fresh", longest + "b"), emptyList()))
    }
}
