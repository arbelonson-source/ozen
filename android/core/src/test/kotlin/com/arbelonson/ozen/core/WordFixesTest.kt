package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordFixesTest {
    private fun fix(heard: String, written: String) = WordFix(heard, written)
    private fun run(text: String, vararg fixes: WordFix) = WordFixes.apply(fixes.toList(), text)

    @Test fun aRefusalSaysWhyAddFixIsGreyAndIsNullExactlyWhenItCanAdd() {
        val none = emptyList<WordFix>()
        assertEquals(WordFixes.Refusal.SameWord, WordFixes.refusal(fix("nasa", "NASA"), none))
        assertEquals(WordFixes.Refusal.HeardWords, WordFixes.refusal(fix("one two three four five", "x"), none))
        assertEquals(WordFixes.Refusal.HeardWords, WordFixes.refusal(fix("ok, dana", "Dana"), none))
        assertEquals(WordFixes.Refusal.TooLong, WordFixes.refusal(fix("a".repeat(41), "b"), none))
        assertEquals(WordFixes.Refusal.Missing, WordFixes.refusal(fix("dana", "  "), none))
        val full = (1..WordFixes.MAXIMUM_FIXES).map { fix("word$it", "Word number $it") }
        assertEquals(WordFixes.Refusal.Full, WordFixes.refusal(fix("dana", "Dana Levi"), full))
        assertNull(WordFixes.refusal(fix("word7", "Seven"), full))
        assertNull(WordFixes.refusal(fix("dana", "Dana Levi"), none))
        val samples = listOf(fix("nasa", "NASA"), fix("a b c d e", "x"), fix("ok, dana", "Dana"), fix("dana", "Dana Levi"), fix("", "x"), fix("שלום", "שלום"), fix("מאיה", "מיה"))
        for (sample in samples) assertEquals(WordFixes.canAdd(sample, none), WordFixes.refusal(sample, none) == null, sample.toString())
    }

    @Test fun aRefusalNoteNamesTheRuleAndAnEmptyBoxNeedsNone() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Up to 40 letters and spaces in each box.", WordFixes.refusalNote(WordFixes.Refusal.TooLong))
        assertEquals("In “Heard”: up to 4 words, with only spaces between them.", WordFixes.refusalNote(WordFixes.Refusal.HeardWords))
        assertEquals("The list is full. Delete a fix to add another.", WordFixes.refusalNote(WordFixes.Refusal.Full))
        assertNull(WordFixes.refusalNote(WordFixes.Refusal.Missing))
        assertNull(WordFixes.refusalNote(null))
    }

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

    @Test fun aHebrewPrefixKeptBeforeLatinLettersOrANumberTakesAMaqaf() {
        val phone = fix("אייפון", "iPhone")
        assertEquals("קניתי ב\u05BEiPhone", run("קניתי באייפון", phone))
        assertEquals("ושב\u05BEiPhone", run("ושבאייפון", phone))
        assertEquals("iPhone חדש", run("אייפון חדש", phone))
        assertEquals("ב\u05BE7", run("בשבע", fix("שבע", "7")))
        assertEquals("ורותי", run("ורותם", fix("רותם", "רותי")))
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

    @Test fun aWordToTapKeepsTheGereshThatEndsANameButNotThePunctuationAfterIt() {
        assertEquals(listOf("שלום", "ג׳ורג׳", "מה", "נשמע"), WordFixes.wordsOf("שלום ג׳ורג׳, מה נשמע?"))
        assertEquals(listOf("ג'ורג'", "בא"), WordFixes.wordsOf("ג'ורג' בא"))
        assertEquals(listOf("ג’ורג’", "בא"), WordFixes.wordsOf("ג’ורג’ בא"))
        assertEquals(listOf("ג׳וֹרְגְ׳", "בא"), WordFixes.wordsOf("ג׳וֹרְגְ׳ בא"))
        assertEquals(listOf("ג׳ורג׳"), WordFixes.wordsOf("\"ג׳ורג׳\"."))
        assertEquals(listOf("פרופ׳", "כהן"), WordFixes.wordsOf("פרופ׳ כהן"))
        assertEquals(listOf("ג׳ורג", "בא"), WordFixes.wordsOf("ג׳ורג בא"))
        assertEquals(listOf("james", "said"), WordFixes.wordsOf("‘james’ said"))
        assertEquals(listOf("James", "car"), WordFixes.wordsOf("James' car"))
    }

    @Test fun normalizedDropsBlankWrittenAndPhrasesApplyCannotUse() {
        assertEquals(emptyList(), WordFixes.normalized(listOf(fix("aval", "   "))))
        assertEquals(emptyList(), WordFixes.normalized(listOf(fix("a b c d e", "X"))))
        assertEquals(listOf(fix("a b c d", "X")), WordFixes.normalized(listOf(fix("a b c d", "X"))))
        assertEquals("a b", run("a b", fix("a", "")))
    }

    @Test fun selectionNeverReachesAcrossPunctuation() {
        val connected = { first: Int -> WordFixes.joinedBySpace("bat, sheva ok", first) }
        assertEquals(false, connected(0))
        assertEquals(true, connected(1))
        assertEquals(1..1, WordFixes.tapped(0..0, 1, connected))
        assertEquals(1..2, WordFixes.tapped(1..1, 2, connected))
    }

    @Test fun aNamesEndingGereshIsNoPunctuationBeforeTheNextHeardWordACommaAfterItIs() {
        for (geresh in listOf("׳", "'", "’")) {
            val name = "ג${geresh}ורג$geresh"
            assertNull(WordFixes.refusal(fix("$name בוש", "George Bush"), emptyList()), name)
        }
        assertEquals(WordFixes.Refusal.HeardWords, WordFixes.refusal(fix("ג׳ורג׳, בוש", "George Bush"), emptyList()))
        assertEquals(WordFixes.Refusal.HeardWords, WordFixes.refusal(fix("james' car", "the car"), emptyList()))
    }

    @Test fun aSelectionReachesPastANamesEndingGereshNotPastACommaAfterIt() {
        for (geresh in listOf("׳", "'", "’")) {
            val line = "ראיתי את ג${geresh}ורג$geresh בוש אתמול"
            val connected = { first: Int -> WordFixes.joinedBySpace(line, first) }
            assertEquals(true, connected(2), line)
            assertEquals(2..3, WordFixes.tapped(2..2, 3, connected), line)
            assertEquals(2..3, WordFixes.tapped(3..3, 2, connected), line)
        }
        assertEquals(false, WordFixes.joinedBySpace("ראיתי את ג׳ורג׳, בוש אתמול", 2))
        assertEquals(false, WordFixes.joinedBySpace("james' car", 0))
    }

    @Test fun aWordToTapLeavesOutTheClosingQuoteOfAQuotedHebrewWordAndKeepsANamesGereshBeforeIt() {
        assertEquals(listOf("שלום", "בא"), WordFixes.wordsOf("‘שלום’ בא"))
        assertEquals(listOf("שלום", "בא"), WordFixes.wordsOf("'שלום' בא"))
        assertEquals(listOf("אמרתי", "שלום", "לכולם"), WordFixes.wordsOf("אמרתי ‘שלום’ לכולם"))
        assertEquals(listOf("ג׳ורג׳", "בוש"), WordFixes.wordsOf("‘ג׳ורג׳ בוש’"))
        assertEquals(listOf("ג׳ורג׳", "בא"), WordFixes.wordsOf("‘ג׳ורג׳’ בא"))
        assertEquals(listOf("ג'ורג'", "בא"), WordFixes.wordsOf("'ג'ורג'' בא"))
        assertEquals(listOf("שלום", "ג’ורג’", "בא"), WordFixes.wordsOf("‘שלום’ ג’ורג’ בא"))
        assertEquals(listOf("hello", "ג’ורג’", "בא"), WordFixes.wordsOf("‘hello’ ג’ורג’ בא"))
        assertEquals(listOf("שלום", "ג'ורג'", "בא"), WordFixes.wordsOf("‘שלום ג'ורג' בא"))
        val shown = "אמרתי ‘שלום’ לכולם"
        val heard = WordFixes.wordsOf(shown)[1]
        assertEquals("אמרתי ‘Shalom’ לכולם", WordFixes.apply(listOf(fix(heard, "Shalom")), shown))
    }

    @Test fun aClosingQuoteKeepsAHeardPhraseFromGoingOnAndAQuotedPhraseStillJoins() {
        assertEquals(false, WordFixes.joinedBySpace("‘שלום’ בא", 0))
        assertEquals(false, WordFixes.joinedBySpace("'שלום' בא", 0))
        assertEquals(WordFixes.Refusal.HeardWords, WordFixes.refusal(fix("‘שלום’ בא", "Shalom ba"), emptyList()))
        val line = "ראיתי את ‘ג׳ורג׳ בוש’ אתמול"
        val connected = { first: Int -> WordFixes.joinedBySpace(line, first) }
        assertEquals(true, connected(2))
        assertEquals(false, connected(3))
        assertEquals(2..3, WordFixes.tapped(2..2, 3, connected))
        assertNull(WordFixes.refusal(fix("‘ג׳ורג׳ בוש’", "George Bush"), emptyList()))
        val picked = WordFixes.wordsOf(line).subList(2, 4).joinToString(" ")
        assertEquals("ג׳ורג׳ בוש", picked)
        assertEquals("ראיתי את ‘George Bush’ אתמול", WordFixes.apply(listOf(fix(picked, "George Bush")), line))
    }
}
