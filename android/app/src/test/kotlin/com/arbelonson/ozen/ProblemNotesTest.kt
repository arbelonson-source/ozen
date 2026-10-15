package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemNotesTest {
    private fun english(block: () -> Unit) = Localization.withLanguage(UILanguage.English, block)

    @Test
    fun `with saving conversations off the marked problem says no caption lines were kept`() = english {
        val note = ProblemNotes.marked(keptSound = false, keptCaptions = false)
        assertFalse(note.contains("last few lines"))
        assertTrue(note.contains("no caption lines"))
    }

    @Test
    fun `with saving on the marked problem still names the lines and the sound`() = english {
        assertTrue(ProblemNotes.marked(keptSound = true, keptCaptions = true).contains("last few lines and the last 30 seconds"))
        assertTrue(ProblemNotes.marked(keptSound = false, keptCaptions = true).contains("details and the last few lines"))
    }

    @Test
    fun `the diagnostics note claims the caption lines only when they are kept`() = english {
        assertTrue(ProblemNotes.diagnostics(keptCaptions = true).contains("last few caption lines"))
        assertFalse(ProblemNotes.diagnostics(keptCaptions = false).contains("caption lines"))
    }
}
