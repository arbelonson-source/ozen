package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnicodePatternsTest {
    @Test
    fun `a cloud transcript squeezes any run of spaces, the wide and no-break kinds too`() {
        assertEquals(listOf("שלום לכולם"), CloudSpeech.turns("A: שלום\u00A0\u2003לכולם"))
    }

    @Test
    fun `a speaker label with a no-break space after speaker is still a label`() {
        assertEquals(listOf("כן", "לא"), CloudSpeech.turns("Speaker\u00A01: כן\nSpeaker\u00A02: לא"))
    }

    @Test
    fun `a phone number right after a digit of another script is not a phone number`() {
        assertEquals(listOf("050-1234567"), PhoneNumbers.matches("התקשרו 050-1234567").map { it.textIn("התקשרו 050-1234567") })
        assertTrue(PhoneNumbers.matches("\u06630501234567").isEmpty())
    }

    @Test
    fun `a star code takes in a digit of another script, as it does an ascii one`() {
        assertEquals("חייגו \u2066*2700\u2069 עכשיו", CaptionLayout.copiedText("חייגו *2700 עכשיו"))
        assertEquals("\u2066*2700\u0663\u2069", CaptionLayout.copiedText("*2700\u0663"))
    }

    @Test
    fun `a recording's number in another script comes off its file name too`() {
        assertEquals("Dana", RecordingImport.personName("Dana \u0662.m4a"))
    }
}
