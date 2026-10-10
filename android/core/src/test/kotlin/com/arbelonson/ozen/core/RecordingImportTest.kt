package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingImportTest {
    @Test
    fun `the person's name is the file name without its number or extension`() {
        assertEquals("Savta", RecordingImport.personName("Savta 1.m4a"))
        assertEquals("Savta", RecordingImport.personName("Savta 2.m4a"))
        assertEquals("סבתא", RecordingImport.personName("סבתא 3.m4a"))
        assertEquals("סבא יוסי", RecordingImport.personName("סבא יוסי 1.wav"))
        assertEquals("Aba", RecordingImport.personName("Aba_2.mp3"))
        assertEquals("Ima", RecordingImport.personName("Ima-12.aac"))
        assertEquals("Dana", RecordingImport.personName("Dana (2).m4a"))
        assertEquals("Dana", RecordingImport.personName("Dana.m4a"))
        assertEquals("Noa", RecordingImport.personName("Noa2.m4a"))
        assertEquals("Bat Sheva", RecordingImport.personName("Bat_Sheva 1.m4a"))
    }

    @Test
    fun `invisible direction marks, as text pasted from a Hebrew message carries them, don't make another person`() {
        assertEquals("סבתא", RecordingImport.personName("סבתא 1‏.m4a"))
        assertEquals("סבתא", RecordingImport.personName("‏סבתא 2.m4a"))
        assertEquals("סבא יוסי", RecordingImport.personName("⁧סבא יוסי⁩ 3.m4a"))
        assertEquals("Dana", RecordingImport.personName("Dana‎ (2).m4a"))
        assertNull(RecordingImport.personName("‏4‏.m4a"))
    }

    @Test
    fun `a date or several numbers after the name all come off`() {
        assertEquals("Dana", RecordingImport.personName("Dana 2024-05-03.m4a"))
        assertEquals("Dana", RecordingImport.personName("Dana 03.05.2024.m4a"))
        assertEquals("Dana", RecordingImport.personName("Dana_2024_05.m4a"))
        assertEquals("דנה", RecordingImport.personName("דנה 05-03.m4a"))
        assertEquals("Dana", RecordingImport.personName("Dana 2024-05-03 (2).m4a"))
        assertEquals(
            "Dana 1234567890123456789012345678901234x",
            RecordingImport.personName("Dana 1234567890123456789012345678901234x.m4a"),
        )
    }

    @Test
    fun `a file named only by a number gives no name`() {
        assertNull(RecordingImport.personName("12.m4a"))
        assertNull(RecordingImport.personName(" .m4a"))
    }

    @Test
    fun `the summary names who was added, how many recordings each, and what was left out`() {
        val result = RecordingImport.Result(added = mapOf("Savta" to 2, "Aba" to 1), unusable = listOf("12.m4a"))
        val english = Localization.withLanguage(UILanguage.English) { result.summary }
        assertEquals("Added: Aba, Savta (2 recordings)\n\nNot added (too little speech, or no name in the file name): 12.m4a", english)
        val onlyAdded = Localization.withLanguage(UILanguage.English) { RecordingImport.Result(added = mapOf("Aba" to 1)).summary }
        assertEquals("Added: Aba", onlyAdded)
    }
}
