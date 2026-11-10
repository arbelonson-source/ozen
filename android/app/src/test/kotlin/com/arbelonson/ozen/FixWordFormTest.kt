package com.arbelonson.ozen

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
}
