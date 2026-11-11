package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakerNamingTest {
    @Test
    fun `a voice with no name yet, or one whose name was never saved, is kept as a new voice print`() {
        assertEquals(SpeakerNaming.NewPrint, SpeakerNaming.of(oldName = null, name = "Dana", savedNames = setOf("Yossi"), isRespelling = true))
        assertEquals(SpeakerNaming.NewPrint, SpeakerNaming.of(oldName = "Dana", name = "Dana", savedNames = emptySet(), isRespelling = true))
    }

    @Test
    fun `a saved person's name with its spelling fixed renames the person`() {
        assertEquals(SpeakerNaming.Rename(from = "Dna"), SpeakerNaming.of(oldName = "Dna", name = "Dana", savedNames = setOf("Dna", "Yossi"), isRespelling = true))
    }

    @Test
    fun `a name that is not a spelling fix, or given to this line only, is someone else, with a print of their own, and the saved person keeps their name, as on the iPhone`() {
        assertEquals(SpeakerNaming.NewPrint, SpeakerNaming.of(oldName = "Dana", name = "Moshe", savedNames = setOf("Dana"), isRespelling = false))
        assertEquals(SpeakerNaming.NewPrint, SpeakerNaming.of(oldName = "Noa", name = "Noam", savedNames = setOf("Noa"), isRespelling = false))
    }

    @Test
    fun `the same name again changes nothing`() {
        assertEquals(SpeakerNaming.Unchanged, SpeakerNaming.of(oldName = "Dana", name = "Dana", savedNames = setOf("Dana"), isRespelling = true))
    }

    @Test
    fun `the name of another saved person means the phone took this voice for the wrong one, so it gets a print of its own`() {
        assertEquals(SpeakerNaming.NewPrint, SpeakerNaming.of(oldName = "Dana", name = "Yossi", savedNames = setOf("Dana", "Yossi"), isRespelling = false))
        assertEquals(SpeakerNaming.NewPrint, SpeakerNaming.of(oldName = "Dana", name = "Yossi", savedNames = setOf("Dana", "Yossi"), isRespelling = true))
    }

    @Test
    fun `another saved person's name moves only the line, even one the phone cannot listen for, so the voice it was taken for keeps its name`() {
        assertEquals(true, SpeakerNaming.movesOnlyTheLine(oldName = "Noa", name = "Noam", savedNames = setOf("Noa", "Noam"), asked = false))
        assertEquals(true, SpeakerNaming.movesOnlyTheLine(oldName = "Noa", name = "Noam", savedNames = setOf("Noa"), asked = true))
        assertEquals(false, SpeakerNaming.movesOnlyTheLine(oldName = "Noa", name = "Noam", savedNames = setOf("Noa"), asked = false))
        assertEquals(false, SpeakerNaming.movesOnlyTheLine(oldName = "Noa", name = "Noa", savedNames = setOf("Noa"), asked = false))
        assertEquals(false, SpeakerNaming.movesOnlyTheLine(oldName = null, name = "Noam", savedNames = setOf("Noam"), asked = false))
    }

    @Test
    fun `Save asks first only when a spelling fix would rename a saved person everywhere, as on the iPhone`() {
        assertEquals("Noa", SpeakerNaming.renamedEverywhere(oldName = "Noa", name = "Noam", savedNames = setOf("Noa"), isRespelling = true))
        assertEquals(null, SpeakerNaming.renamedEverywhere(oldName = "Noa", name = "Noam", savedNames = setOf("Noa"), isRespelling = false))
        assertEquals(null, SpeakerNaming.renamedEverywhere(oldName = "Noa", name = "Noa", savedNames = setOf("Noa"), isRespelling = true))
        assertEquals(null, SpeakerNaming.renamedEverywhere(oldName = "Noa", name = "Noam", savedNames = setOf("Noa", "Noam"), isRespelling = true))
        assertEquals(null, SpeakerNaming.renamedEverywhere(oldName = "Noa", name = "Noam", savedNames = emptySet(), isRespelling = true))
        assertEquals(null, SpeakerNaming.renamedEverywhere(oldName = null, name = "Noam", savedNames = setOf("Noa"), isRespelling = true))
    }

    @Test
    fun `whenever Save would ask, saving everywhere renames the saved person`() {
        assertEquals(SpeakerNaming.Rename(from = "Noa"), SpeakerNaming.of(oldName = "Noa", name = "Noam", savedNames = setOf("Noa"), isRespelling = true))
    }
}
