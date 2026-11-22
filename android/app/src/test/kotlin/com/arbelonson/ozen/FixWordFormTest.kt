package com.arbelonson.ozen

import com.arbelonson.ozen.core.WordFix
import com.arbelonson.ozen.core.WordFixes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class FixWordFormTest {
    private val line = "I spoke with Shera yesterday"

    @Test
    fun aTypedWordThatIsNotInTheLineCannotBeSavedAndTheDialogSaysSoWhileOneFromTheLineCan() {
        assertTrue(FixWordForm.changes(line, heard = "shera", written = "Shira"))
        assertTrue(FixWordForm.changes(line, heard = "spoke with", written = "spoke to"))
        assertNull(FixWordForm.note(line, typed = "shera", heard = "shera", written = "Shira"))
        assertFalse(FixWordForm.changes(line, heard = "zzzz", written = "Avi"))
        assertEquals(FixWordForm.Note.NotInLine, FixWordForm.note(line, typed = "zzzz", heard = "zzzz", written = "Avi"))
        assertEquals(FixWordForm.Note.NotInLine, FixWordForm.note(line, typed = "Shira", heard = "Shira", written = ""))
    }

    @Test
    fun aWordPickedOrTypedWithNothingWrittenYetAsksForHowItShouldBeWritten() {
        assertEquals(FixWordForm.Note.NowWrite, FixWordForm.note(line, typed = "", heard = "Shera", written = ""))
        assertEquals(FixWordForm.Note.NowWrite, FixWordForm.note(line, typed = "Shera", heard = "Shera", written = "  "))
        assertNull(FixWordForm.note(line, typed = "", heard = "", written = ""))
        assertNull(FixWordForm.note(line, typed = "", heard = "Shera", written = "Shira"))
    }

    @Test
    fun aPickedNameKeepsItsEndingGereshAndSavingItFixesTheWholeName() {
        val shown = "שלום ג׳ורג׳, מה נשמע?"
        val heard = WordFixes.wordsOf(shown)[1]
        assertEquals("ג׳ורג׳", heard)
        assertTrue(FixWordForm.changes(shown, heard, written = "George"))
        assertEquals(FixWordForm.Note.NowWrite, FixWordForm.note(shown, typed = "", heard = heard, written = ""))
        assertEquals("שלום George, מה נשמע?", WordFixes.apply(listOf(WordFix(heard, "George")), shown))
        assertEquals(listOf(WordFix(heard, "George")), WordFixes.adding(WordFix(heard, "George"), listOf(WordFix("ג׳ורג", "Georg"))))
        assertEquals("George בא", WordFixes.apply(listOf(WordFix(heard, "George")), "ג'ורג' בא"))
    }

    @Test
    fun aPickNoLongerAllInALineThatCameBackShorterIsDroppedWhileOneStillInItStays() {
        assertEquals(11..11, FixWordForm.pick(11, 11, words = 12))
        assertNull(FixWordForm.pick(11, 11, words = 6))
        assertNull(FixWordForm.pick(5, 6, words = 6))
        assertEquals(4..5, FixWordForm.pick(4, 5, words = 6))
        assertNull(FixWordForm.pick(-1, -1, words = 6))
        assertNull(FixWordForm.pick(0, 0, words = 0))
    }

    @Test
    fun fixItEveryTimeWithTheListFullSaysSoAsTheWordsScreenDoesUnlessTheFixReplacesOneAlreadyThere() {
        val full = (0 until WordFixes.MAXIMUM_FIXES).map { WordFix("word$it", "Word $it") }
        val shown = "I saw shera and word7 today"
        assertEquals(WordFixes.Refusal.Full, FixWordForm.keepRefusal(shown, heard = "shera", written = "Shira", fixes = full))
        assertNull(FixWordForm.keepRefusal(shown, heard = "word7", written = "Word Seven", fixes = full))
        assertNull(FixWordForm.keepRefusal(shown, heard = "shera", written = "Shira", fixes = full.dropLast(1)))
        assertNull(FixWordForm.keepRefusal(shown, heard = "shera", written = "", fixes = full))
        assertNull(FixWordForm.keepRefusal(shown, heard = "zzzz", written = "Avi", fixes = full))
    }
}
