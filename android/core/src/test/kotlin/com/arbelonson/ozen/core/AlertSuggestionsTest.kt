package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertSuggestionsTest {
    private fun hasHebrew(text: String): Boolean = text.codePoints().anyMatch { it in 0x0590..0x05FF }

    @Test
    fun `outside Hebrew a suggested word shows only its meaning, with no Hebrew letters`() {
        assertEquals("סבתא", AlertSuggestions.label("סבתא", UILanguage.Hebrew))
        assertEquals("Grandma", AlertSuggestions.label("סבתא", UILanguage.English))
        assertEquals("Мама", AlertSuggestions.label("אמא", UILanguage.Russian))
        assertEquals("Médecin", AlertSuggestions.label("רופא", UILanguage.French))
        assertEquals("Dana", AlertSuggestions.label("Dana", UILanguage.English))
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            for (word in AlertSuggestions.words) {
                val label = AlertSuggestions.label(word, language)
                assertTrue(label.isNotEmpty() && !hasHebrew(label), "$language: $word")
            }
        }
    }

    @Test
    fun `a suggested word she added is named by its meaning in the list, the banner and the notification`() {
        assertEquals("Grandma", AlertSuggestions.shown("סבתא", UILanguage.English))
        assertEquals("סבתא", AlertSuggestions.shown("סבתא", UILanguage.Hebrew))
        assertEquals(AlertSuggestions.label("תרופה", UILanguage.German), AlertSuggestions.shown("תרופה", UILanguage.German))
        assertEquals("דנה", AlertSuggestions.shown("דנה", UILanguage.English))

        val heard = KeywordMatch(UUID.randomUUID(), "סבתא", "סבתוש", 0)
        assertEquals("Grandma", AlertSuggestions.said(heard, UILanguage.English))
        assertEquals("סבתוש", AlertSuggestions.said(heard, UILanguage.Hebrew))
        val own = KeywordMatch(UUID.randomUUID(), "דנה", "לדנה", 0)
        assertEquals("לדנה", AlertSuggestions.said(own, UILanguage.English))
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            for (word in AlertSuggestions.words) {
                val match = KeywordMatch(UUID.randomUUID(), word, word, 0)
                assertFalse(hasHebrew(AlertSuggestions.shown(word, language)), "$language: $word")
                assertFalse(hasHebrew(AlertSuggestions.said(match, language)), "$language: $word")
            }
        }
    }

    @Test
    fun `the note under the name form lists only the words that will buzz, and is gone when none will`() {
        val alerts = listOf(KeywordAlert(phrase = "סבתא"), KeywordAlert(phrase = "Dani", isEnabled = false), KeywordAlert(phrase = "Ruti"))
        assertEquals("The phone will vibrate for: Grandma, Ruti", AlertSuggestions.vibrationNote(alerts, UILanguage.English))
        assertEquals("הטלפון ירטוט על: סבתא, Ruti", AlertSuggestions.vibrationNote(alerts, UILanguage.Hebrew))
        assertNull(AlertSuggestions.vibrationNote(listOf(KeywordAlert(phrase = "Dani", isEnabled = false)), UILanguage.English))
        assertNull(AlertSuggestions.vibrationNote(emptyList(), UILanguage.English))
    }
}
