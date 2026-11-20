package com.arbelonson.ozen

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
}
