package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WordFixesTest {
    private fun fix(heard: String, written: String) = WordFix(heard, written)
    private fun run(text: String, vararg fixes: WordFix) = WordFixes.apply(fixes.toList(), text)

    @Test fun rule1WholeWordsIgnoringLatinCase() {
        val avi = fix("aval", "Avi")
        assertEquals("I saw Avi today", run("I saw aval today", avi))
        assertEquals("I saw Avi today", run("I saw Aval today", avi))
        assertEquals("is available", run("is available", avi))
    }

    @Test fun rule2PunctuationStays() {
        val avi = fix("aval", "Avi")
        assertEquals("Avi,", run("aval,", avi))
        assertEquals("(Avi)", run("(aval)", avi))
        assertEquals("Avi?", run("aval?", avi))
    }

    @Test fun rule3HebrewPrefixKept() {
        val f = fix("רותם", "רותי")
        assertEquals("ורותי באה", run("ורותם באה", f))
        assertEquals("לרותי", run("לרותם", f))
        assertEquals("שלרותי", run("שלרותם", f))
        assertEquals("רותי", run("רותם", f))
        assertEquals("גרותם", run("גרותם", f))
        assertEquals("ושלהרותם", run("ושלהרותם", f))
    }

    @Test fun rule3PrefixNotAppliedToLatin() {
        assertEquals("lavi", run("lavi", fix("avi", "Avi")))
    }

    @Test fun rule4NiqqudIgnoredAndWrittenExact() {
        val f = fix("רותם", "רֻתִי")
        assertEquals("רֻתִי", run("רוּתָם", f))
        assertEquals("Avi", run("a", fix("a", "Avi")))
    }

    @Test fun rule5PhraseAcrossSingleSpaces() {
        val f = fix("bat sheva", "Batsheva")
        assertEquals("I met Batsheva.", run("I met Bat Sheva.", f))
        assertEquals("bat  sheva", run("bat  sheva", f))
        assertEquals("bat, sheva", run("bat, sheva", f))
    }

    @Test fun rule5PhraseLimitIsFourWords() {
        assertEquals("X", run("a b c d", fix("a b c d", "X")))
        assertEquals("a b c d e", run("a b c d e", fix("a b c d e", "X")))
    }

    @Test fun rule6LongerPhraseWins() {
        val fixes = arrayOf(fix("bat", "B"), fix("bat sheva", "Batsheva"))
        assertEquals("Batsheva ok", run("bat sheva ok", *fixes))
    }

    @Test fun rule6NoChaining() {
        assertEquals("b", run("a", fix("a", "b"), fix("b", "c")))
        assertEquals("b c", run("a b", fix("a", "b"), fix("b", "c")).let { it })
    }

    @Test fun rule7Normalized() {
        val out = WordFixes.normalized(
            listOf(
                fix("  aval ", " Avi "),
                fix("", "x"),
                fix("same", "SAME"),
                fix("AVAL", "Other"),
                fix("b".repeat(50), "c".repeat(50)),
            ),
        )
        assertEquals(listOf(fix("aval", "Avi"), fix("b".repeat(40), "c".repeat(40))), out)
    }

    @Test fun rule7SameIgnoringNiqqudDropped() {
        assertEquals(emptyList(), WordFixes.normalized(listOf(fix("רוּתם", "רותם"))))
    }

    @Test fun rule7MaximumHundred() {
        val many = (1..150).map { fix("w$it", "v$it") }
        val out = WordFixes.normalized(many)
        assertEquals(100, out.size)
        assertEquals(fix("w1", "v1"), out.first())
    }

    @Test fun rule8NoFixesLeavesTextAlone() {
        assertEquals("Hello  there", WordFixes.apply(emptyList(), "Hello  there"))
    }

    @Test
    fun tappingWordsBuildsAPhraseOfUpToFour() {
        var at: IntRange? = null
        at = WordFixes.tapped(at, 2)
        assertEquals(2..2, at)
        at = WordFixes.tapped(at, 3)
        assertEquals(2..3, at)
        at = WordFixes.tapped(at, 1)
        assertEquals(1..3, at)
        at = WordFixes.tapped(at, 0)
        assertEquals(0..3, at)
        assertEquals(5..5, WordFixes.tapped(at, 5))
        assertEquals(1..3, WordFixes.tapped(0..3, 0))
        assertEquals(null, WordFixes.tapped(2..2, 2))
        assertEquals(listOf("Hello", "wor'ld"), WordFixes.wordsOf("Hello, wor'ld!"))
    }
}
