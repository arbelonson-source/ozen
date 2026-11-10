package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `no marked problem note sends her through Settings to Diagnostics to Send report, since the button sends it`() {
        for (keptSound in listOf(true, false)) {
            for (keptCaptions in listOf(true, false)) {
                english { assertFalse(ProblemNotes.marked(keptSound, keptCaptions).contains("Settings → Diagnostics → Send report")) }
                Localization.withLanguage(UILanguage.Hebrew) { assertFalse(ProblemNotes.marked(keptSound, keptCaptions).contains("הגדרות ← אבחון ← שליחת הדוח")) }
            }
        }
    }

    @Test
    fun `with the sound kept the note says the button sends the details and lines and the sound is sent from Diagnostics`() = english {
        assertTrue(ProblemNotes.marked(keptSound = true, keptCaptions = true).endsWith(" The button sends the details and lines; the sound is sent from Settings → Diagnostics."))
    }

    @Test
    fun `the report's first line says when the problem was marked, in the app's language`() {
        val at = LocalDateTime.of(2026, 11, 9, 14, 2).toEpochSecond(ZoneOffset.UTC).toDouble()
        english { assertEquals("Ozen on this phone: a captions problem was marked at ${clockTime(at, ZoneOffset.UTC)}.", ProblemNotes.reportLine(at, ZoneOffset.UTC)) }
        Localization.withLanguage(UILanguage.Hebrew) { assertEquals("אוזן בטלפון הזה: סומנה בעיה בכתוביות בשעה 14:02.", ProblemNotes.reportLine(at, ZoneOffset.UTC)) }
    }

    @Test
    fun `the diagnostics note claims the caption lines only when they are kept`() = english {
        assertTrue(ProblemNotes.diagnostics(keptCaptions = true).contains("last few caption lines"))
        assertFalse(ProblemNotes.diagnostics(keptCaptions = false).contains("caption lines"))
    }
}
