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

    @Test
    fun `a curly apostrophe typed on the phone matches the straight one the engine writes, and the reverse`() {
        assertEquals("I met Neil today", run("I met O'Neil today", fix("O\u2019Neil", "Neil")))
        assertEquals("I met Neil today", run("I met O\u2019Neil today", fix("O'Neil", "Neil")))
        assertEquals("I met Neil today", run("I met O'Neil today", fix("O\u02BCNeil", "Neil")))
        assertEquals(1, WordFixes.normalized(listOf(fix("O\u2019Neil", "Neil"), fix("O'Neil", "Neil"))).size)
    }

    @Test
    fun `Arabic vowel marks, tatweel and the alef's hamza are looked past when matching, as for alert words, but not the alef maqsura or ta marbuta`() {
        val ahmad = "\u0623\u062D\u0645\u062F"
        val typed = "\u0627\u062D\u0645\u062F"
        assertEquals("hi Ahmad hi", run("hi $ahmad hi", fix(typed, "Ahmad")))
        assertEquals("hi Ahmad hi", run("hi $typed hi", fix(ahmad, "Ahmad")))
        assertEquals("Muhammad", run("\u0645\u064F\u062D\u0645\u0640\u062F", fix("\u0645\u062D\u0645\u062F", "Muhammad")))
        assertEquals("\u0645\u0648\u0633\u0649", run("\u0645\u0648\u0633\u0649", fix("\u0645\u0648\u0633\u064A", "Musa")))
        assertEquals("\u0641\u0627\u0637\u0645\u0629", run("\u0641\u0627\u0637\u0645\u0629", fix("\u0641\u0627\u0637\u0645\u0647", "Fatima")))
        assertEquals("hi Jos\u00E9", run("hi Jos\u00E9", fix("jose", "Joseph")))
    }

    @Test
    fun `a short Hebrew name does not rewrite the everyday word it hides in`() {
        val lea = "\u05DC\u05D0\u05D4"
        val full = "\u05DE\u05DC\u05D0\u05D4"
        assertEquals(full, run(full, fix(lea, "\u05DC\u05D9\u05D4")))
        assertEquals("\u05D5\u05DC\u05D9\u05D4", run("\u05D5\u05DC\u05D0\u05D4", fix(lea, "\u05DC\u05D9\u05D4")))
        assertEquals("\u05DC\u05D9\u05D4", run(lea, fix(lea, "\u05DC\u05D9\u05D4")))
        val whenFull = "\u05DB\u05E9\u05DE\u05DC\u05D0\u05D4"
        assertEquals(whenFull, run(whenFull, fix(lea, "\u05DC\u05D9\u05D4")))
        val sara = "\u05E9\u05E8\u05D4"
        val job = "\u05DE\u05E9\u05E8\u05D4"
        val kosher = "\u05DB\u05E9\u05E8\u05D4"
        assertEquals("$job $kosher", run("$job $kosher", fix(sara, "\u05E9\u05E8\u05D9")))
        assertEquals("\u05DC\u05E9\u05E8\u05D9", run("\u05DC\u05E9\u05E8\u05D4", fix(sara, "\u05E9\u05E8\u05D9")))
        assertEquals("\u05D5\u05DC\u05E9\u05E8\u05D9", run("\u05D5\u05DC\u05E9\u05E8\u05D4", fix(sara, "\u05E9\u05E8\u05D9")))
    }

    @Test
    fun `a name does not rewrite the everyday Arabic word that differs only in a final ya or alef maqsura`() {
        val on = "وضع الكتاب على الطاولة"
        assertEquals(on, run(on, fix("علي", "Ali")))
        assertEquals("قال Ali اليوم", run("قال علي اليوم", fix("علي", "Ali")))
        assertEquals("قال Ali اليوم", run("قال عليّ اليوم", fix("علي", "Ali")))
    }

    @Test
    fun `a fix that only adds a hamza is allowed, kept and applied once`() {
        val hamza = fix("احمد", "أحمد")
        assertEquals(null, WordFixes.refusal(hamza, emptyList()))
        assertEquals(listOf(hamza), WordFixes.normalized(listOf(hamza)))
        assertEquals("قال أحمد اليوم", run("قال احمد اليوم", hamza))
        assertEquals("قال أحمد اليوم", run("قال أحمد اليوم", hamza))
        assertEquals("قال أحمد اليوم", run("قال إحمد اليوم", hamza))
        val curly = fix("don\u2019t", "don't")
        assertEquals(null, WordFixes.refusal(curly, emptyList()))
        assertEquals(listOf(curly), WordFixes.normalized(listOf(curly)))
        assertEquals(WordFixes.Refusal.SameWord, WordFixes.refusal(fix("nasa", "NASA"), emptyList()))
    }

    @Test
    fun `an Arabic word with a mark inside, between Arabic words, is replaced and the rest kept exactly`() {
        assertEquals("قال Muhammad اليوم", run("قال مُحمد اليوم", fix("محمد", "Muhammad")))
    }

    @Test
    fun `a curly apostrophe inside the heard word matches next to a full stop and keeps it`() {
        assertEquals("I do not.", run("I don\u2019t.", fix("don't", "do not")))
        assertEquals("Wait, do not!", run("Wait, don't!", fix("don\u2019t", "do not")))
    }

    @Test
    fun `a fix that adds words is not written twice where the line spells the name with the other apostrophe or with vowel marks`() {
        val smith = fix("O\u2019Neil", "O\u2019Neil Smith")
        assertEquals("I met O'Neil Smith", run("I met O'Neil Smith", smith))
        assertEquals("I met O\u2019Neil Smith", run("I met O'Neil", smith))
        val surname = fix("احمد", "أحمد علي")
        assertEquals("قال أَحمد علي", run("قال أَحمد علي", surname))
        assertEquals("قال أحمد علي", run("قال احمد", surname))
    }

    @Test
    fun `a fix that only respells its word still applies where the line spells it another way, and can take vowel marks away`() {
        val plain = fix("مُحمّد", "محمد")
        assertEquals(listOf(plain), WordFixes.normalized(listOf(plain)))
        assertEquals("قال محمد", run("قال مُحَمَّد", plain))
        assertEquals("قال محمد", run("قال مُحمّد", plain))
        assertEquals("قال محمد", run("قال محمد", plain))
    }

    @Test
    fun `a dotted capital I matches its small i, and a fix that only adds the dot is kept, the same on both phones`() {
        assertEquals("Constantinople g\u00FCzel", run("\u0130stanbul g\u00FCzel", fix("istanbul", "Constantinople")))
        val dot = fix("izmir", "\u0130zmir")
        assertEquals(null, WordFixes.refusal(dot, emptyList()))
        assertEquals("\u0130zmir ve \u0130zmir", run("izmir ve \u0130zmir", dot))
        assertEquals(WordFixes.Refusal.SameWord, WordFixes.refusal(fix("istanbul", "Istanbul"), emptyList()))
    }

    @Test
    fun `an acronym or a name typed with the phone's curly quote, gershayim or geresh matches the engine's straight one`() {
        assertEquals("\u05D3\u05D5\u05E7\u05D8\u05D5\u05E8 \u05DB\u05D4\u05DF", run("\u05D3\"\u05E8 \u05DB\u05D4\u05DF", fix("\u05D3\u201D\u05E8", "\u05D3\u05D5\u05E7\u05D8\u05D5\u05E8")))
        assertEquals("IDF \u05D1\u05D0", run("\u05E6\u05D4\"\u05DC \u05D1\u05D0", fix("\u05E6\u05D4\u05F4\u05DC", "IDF")))
        assertEquals("Charlie \u05D1\u05D0", run("\u05E6'\u05E8\u05DC\u05D9 \u05D1\u05D0", fix("\u05E6\u05F3\u05E8\u05DC\u05D9", "Charlie")))
        assertEquals("Charlie \u05D1\u05D0", run("\u05E6\u05F3\u05E8\u05DC\u05D9 \u05D1\u05D0", fix("\u05E6'\u05E8\u05DC\u05D9", "Charlie")))
    }

    @Test
    fun `a heard word of only vowel marks or tatweel can't be added and changes nothing`() {
        assertEquals(WordFixes.Refusal.HeardWords, WordFixes.refusal(fix("\u05B8", "x"), emptyList()))
        assertEquals(WordFixes.Refusal.HeardWords, WordFixes.refusal(fix("\u0640", "x"), emptyList()))
        assertEquals(emptyList(), WordFixes.normalized(listOf(fix("\u064E", "x"))))
        assertEquals("a \u0640 b", run("a \u0640 b", fix("\u0640", "x")))
    }
}
