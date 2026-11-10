package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpokenLanguageNoteTest {
    @Test
    fun `Amharic on the phone's model or the home computer says captions are often wrong`() {
        Localization.withLanguage(UILanguage.English) {
            val note = "Captions in Amharic are often wrong for now. Check anything important with the person speaking."
            assertEquals(note, SpokenLanguageNote.text(UILanguage.Amharic, TranscriptionEngineKind.WhisperKit))
            assertEquals(note, SpokenLanguageNote.text(UILanguage.Amharic, TranscriptionEngineKind.HomeServer))
            assertEquals(note, SpokenLanguageNote.text(UILanguage.Amharic, TranscriptionEngineKind.AppleSpeech))
        }
    }

    @Test
    fun `a cloud service, or any other language, adds nothing`() {
        assertNull(SpokenLanguageNote.text(UILanguage.Amharic, TranscriptionEngineKind.Cloud))
        for (language in UILanguage.entries.filter { it != UILanguage.Amharic }) {
            for (engine in TranscriptionEngineKind.entries) {
                assertNull(SpokenLanguageNote.text(language, engine), "$language $engine")
            }
        }
    }
}
