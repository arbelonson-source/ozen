package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedPersonMergeTest {
    private val saved = listOf("Dana", "Yossi")

    @Test
    fun `a name another saved person has is a merge into that person`() {
        assertEquals("Yossi", SavedPersonMerge.target("Dana", "Yossi", saved))
    }

    @Test
    fun `case and spaces around the name do not hide it, and the saved spelling is the one to keep`() {
        assertEquals("Yossi", SavedPersonMerge.target("Dana", "  yossi ", saved))
    }

    @Test
    fun `an unused name, the person's own name, or a case-only change of it is no merge`() {
        assertNull(SavedPersonMerge.target("Dana", "Dani", saved))
        assertNull(SavedPersonMerge.target("Dana", "Dana", saved))
        assertNull(SavedPersonMerge.target("Dana", "dana", saved))
        assertNull(SavedPersonMerge.target("Dana", "   ", saved))
    }

    @Test
    fun `a person saved with several voice prints is no other person`() {
        assertNull(SavedPersonMerge.target("Dana", "Daniela", listOf("Dana", "Dana", "Yossi")))
    }

    @Test
    fun `a merge lands on the saved name exactly as stored, even with spaces around it, so no person is left apart`() {
        val padded = listOf("Dana", "Yossi ")
        assertEquals("Yossi ", SavedPersonMerge.target("Dana", "yossi", padded))
        assertEquals("Yossi ", SavedPersonMerge.destination("Dana", "yossi", padded))
        assertEquals("Dani", SavedPersonMerge.destination("Dana", "  Dani ", padded))
    }

    @Test
    fun `a voice with no name yet, named after a saved person, lands on that person as saved`() {
        val padded = listOf("Dana", "Yossi ")
        assertEquals("Yossi ", SavedPersonMerge.destination(null, "yossi", padded))
        assertEquals("Dani", SavedPersonMerge.destination(null, " Dani ", padded))
    }

    @Test
    fun `the merge question names the saved person without the spaces around the saved name`() = Localization.withLanguage(UILanguage.English) {
        val (question, explanation) = SavedPersonMerge.question("Yossi ")
        assertEquals("Merge with Yossi?", question)
        assertEquals("Yossi is already saved. Both voices will then be shown under that one name, including in past conversations.", explanation)
    }

    @Test
    fun `with two people saved as Yossi, one with a space after the name, the exact saved name is the one meant, whichever was saved first`() {
        assertEquals("Yossi", SavedPersonMerge.target("Dana", "Yossi", listOf("Dana", "Yossi ", "Yossi")))
        assertEquals("Yossi", SavedPersonMerge.destination(null, "Yossi", listOf("Yossi ", "Yossi")))
        assertEquals("Yossi ", SavedPersonMerge.target("Dana", "Yossi ", listOf("Dana", "Yossi", "Yossi ")))
    }
}
