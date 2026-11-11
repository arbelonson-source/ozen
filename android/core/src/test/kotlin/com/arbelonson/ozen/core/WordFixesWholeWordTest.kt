package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WordFixesWholeWordTest {
    private fun fix(heard: String, written: String) = WordFix(heard, written)
    private fun run(text: String, vararg fixes: WordFix) = WordFixes.apply(fixes.toList(), text)

    @Test
    fun `a fix for a number leaves a decimal and a time alone`() {
        assertEquals("3.5", run("3.5", fix("5", "חמש")))
        assertEquals("10:30", run("10:30", fix("30", "thirty")))
        assertEquals("יש חמש.", run("יש 5.", fix("5", "חמש")))
    }

    @Test
    fun `a Greek fix finds its word in capitals or small letters, final sigma and all`() {
        assertEquals("ο Logos μας", run("ο λογος μας", fix("ΛΟΓΟΣ", "Logos")))
        assertEquals("Ο Logos", run("Ο ΛΟΓΟΣ", fix("λογος", "Logos")))
    }

    @Test
    fun `heard words may be joined by one maqaf, a different fix from the same words with a space`() {
        val maqaf = fix("תל\u05BEאביב", "Tel Aviv")
        val space = fix("תל אביב", "TLV")
        assertEquals(listOf(maqaf, space), WordFixes.normalized(listOf(maqaf, space)))
        for (heard in listOf("תל \u05BEאביב", "תל\u05BE\u05BEאביב", "א\u05BEב\u05BEג\u05BEד\u05BEה")) {
            assertEquals(WordFixes.Refusal.HeardWords, WordFixes.refusal(fix(heard, "x"), emptyList()), heard)
        }
    }

    @Test
    fun `a heard word with a hyphen is one word, kept and applied`() {
        val mail = fix("e-mail", "email")
        assertEquals(listOf(mail), WordFixes.normalized(listOf(mail)))
        assertEquals("my email.", run("my E-mail.", mail))
        assertEquals("my e-mail", run("my e-mail", fix("mail", "post")))
    }

    @Test
    fun `a fix that only adds a maqaf between two heard words is kept`() {
        assertEquals(1, WordFixes.normalized(listOf(fix("תל אביב", "תל\u05BEאביב"))).size)
    }

    @Test
    fun `the words offered for a fix are whole words`() {
        assertEquals(
            listOf("costs", "3.5", "by", "e-mail", "at", "10:30"),
            WordFixes.wordsOf("costs 3.5 by e-mail, at 10:30."),
        )
        assertEquals(true, WordFixes.joinedBySpace("3.5 e-mail ok", 0))
        assertEquals(false, WordFixes.joinedBySpace("3.5, e-mail ok", 0))
    }

    @Test
    fun `a fix on a two-letter word leaves longer words that end with it alone`() {
        val lev = fix("לב", "ליב")
        assertEquals("כלב", run("כלב", lev))
        assertEquals("שלב", run("שלב", lev))
        assertEquals("ליב", run("לב", lev))
        assertEquals("מגל", run("מגל", fix("גל", "גיל")))
        assertEquals("ורותי", run("ורותם", fix("רותם", "רותי")))
        assertEquals("לדור", run("לדוד", fix("דוד", "דור")))
    }

    @Test
    fun `a phrase that starts with a two-letter word still keeps a prefix`() {
        val name = fix("דן כהן", "דן כוהן")
        assertEquals("לדן כוהן", run("לדן כהן", name))
        assertEquals("דן כוהן", run("דן כהן", name))
    }

    @Test
    fun `a prefix letter is kept only before a heard word that starts with a Hebrew letter`() {
        assertEquals("ב5", run("ב5", fix("5", "חמש")))
        assertEquals("ב100", run("ב100", fix("100", "מאה")))
    }
}
