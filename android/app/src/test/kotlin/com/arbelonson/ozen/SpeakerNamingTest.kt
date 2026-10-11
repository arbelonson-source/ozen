package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakerNamingTest {
    @Test
    fun `a voice with no name yet, or one whose name was never saved, is kept as a new voice print`() {
        assertEquals(SpeakerNaming.NewPrint, SpeakerNaming.of(oldName = null, name = "Dana", savedNames = setOf("Yossi")))
        assertEquals(SpeakerNaming.NewPrint, SpeakerNaming.of(oldName = "Dana", name = "Dana", savedNames = emptySet()))
    }

    @Test
    fun `a saved person's voice given a new name is a spelling fixed, so the person is renamed`() {
        assertEquals(SpeakerNaming.Rename(from = "Dna"), SpeakerNaming.of(oldName = "Dna", name = "Dana", savedNames = setOf("Dna", "Yossi")))
    }

    @Test
    fun `the same name again changes nothing`() {
        assertEquals(SpeakerNaming.Unchanged, SpeakerNaming.of(oldName = "Dana", name = "Dana", savedNames = setOf("Dana")))
    }

    @Test
    fun `the name of another saved person means the phone took this voice for the wrong one, so it gets a print of its own`() {
        assertEquals(SpeakerNaming.NewPrint, SpeakerNaming.of(oldName = "Dana", name = "Yossi", savedNames = setOf("Dana", "Yossi")))
    }
}
