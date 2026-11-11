package com.arbelonson.ozen

import com.arbelonson.ozen.core.HebrewText
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineSummaryTest {
    private val described = listOf(TranscriptionEngineKind.Cloud, TranscriptionEngineKind.HomeServer)

    @Test
    fun `the engine summaries say Hebrew only while Hebrew is the spoken language, as on the iPhone`() = Localization.withLanguage(UILanguage.English) {
        for (kind in described) assertTrue(engineSummary(kind, UILanguage.Hebrew)!!.contains("Hebrew"), "$kind")
        for (spoken in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            for (kind in described) assertFalse(engineSummary(kind, spoken)!!.contains("Hebrew"), "$kind for $spoken")
        }
        assertNull(engineSummary(TranscriptionEngineKind.WhisperKit, UILanguage.Spanish))
    }

    @Test
    fun `the summaries for another spoken language are translated, not left in English`() {
        for (kind in described) {
            val english = Localization.withLanguage(UILanguage.English) { engineSummary(kind, UILanguage.Spanish) }!!
            for (shown in UILanguage.entries.filter { it != UILanguage.English }) {
                val summary = Localization.withLanguage(shown) { engineSummary(kind, UILanguage.Spanish) }!!
                assertNotEquals(english, HebrewText.removingDirectionMarks(summary), "$kind in $shown")
            }
        }
    }
}
