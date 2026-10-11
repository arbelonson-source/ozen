package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptionLineSpeechTest {
    @Test
    fun `a line is heard in one go, its marks first, then who said it, then the words, as on the iPhone`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Take two pills", CaptionLineSpeech.label("Take two pills", speaker = null, starred = false, unsure = false))
        assertEquals("Dana: Take two pills", CaptionLineSpeech.label("Take two pills", speaker = "Dana", starred = false, unsure = false))
        assertEquals("Marked as important. Take two pills", CaptionLineSpeech.label("Take two pills", speaker = null, starred = true, unsure = false))
        assertEquals("May not have been heard correctly. Dana: Take two pills", CaptionLineSpeech.label("Take two pills", speaker = "Dana", starred = false, unsure = true))
        assertEquals(
            "Marked as important. May not have been heard correctly. Dana: Take two pills",
            CaptionLineSpeech.label("Take two pills", speaker = "Dana", starred = true, unsure = true),
        )
    }

    @Test
    fun `after the words come an important word in the line and the words that may be wrong`() = Localization.withLanguage(UILanguage.English) {
        assertNull(CaptionLineSpeech.state(important = false, doubtful = emptyList()))
        assertEquals("Contains an important word", CaptionLineSpeech.state(important = true, doubtful = emptyList()))
        assertEquals("Words that may be wrong: pills", CaptionLineSpeech.state(important = false, doubtful = listOf("pills")))
        assertEquals(
            "Contains an important word. Words that may be wrong: two, pills",
            CaptionLineSpeech.state(important = true, doubtful = listOf("two", "pills")),
        )
    }
}
