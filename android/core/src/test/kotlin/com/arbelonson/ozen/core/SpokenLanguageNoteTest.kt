package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpokenLanguageNoteTest {
    @Test
    fun `Amharic says captions are often wrong`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals(
                "Captions in Amharic are often wrong for now. Check anything important with the person speaking.",
                SpokenLanguageNote.text(UILanguage.Amharic),
            )
        }
    }

    @Test
    fun `any other language adds nothing`() {
        for (language in UILanguage.entries.filter { it != UILanguage.Amharic }) {
            assertNull(SpokenLanguageNote.text(language), "$language")
        }
    }
}
