package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SliderSpeechTest {
    @Test
    fun `the speech rate is spoken as a word and a number, as on the iPhone`() {
        assertEquals("Slow, 0.20", SliderSpeech.speechRate(0.2, UILanguage.English))
        assertEquals("Normal, 0.45", SliderSpeech.speechRate(0.45, UILanguage.English))
        assertEquals("Fast, 0.70", SliderSpeech.speechRate(0.7, UILanguage.English))
    }

    @Test
    fun `the number is spoken with the language's decimal comma, as the screen writes it`() {
        assertTrue(SliderSpeech.speechRate(0.45, UILanguage.German).endsWith(" 0,45"))
        assertTrue(SliderSpeech.speakerSeparation(0.6, UILanguage.French).endsWith(" 0,60"))
        assertTrue(SliderSpeech.speakerSeparation(0.6, UILanguage.English).endsWith(" 0.60"))
    }

    @Test
    fun `the text size is spoken with its unit`() {
        assertEquals("Points: 34", SliderSpeech.textSize(34.0, UILanguage.English))
    }

    @Test
    fun `Hebrew and English differ`() {
        for (rate in listOf(0.2, 0.45, 0.7)) {
            assertNotEquals(SliderSpeech.speechRate(rate, UILanguage.Hebrew), SliderSpeech.speechRate(rate, UILanguage.English))
        }
        assertNotEquals(SliderSpeech.textSize(34.0, UILanguage.Hebrew), SliderSpeech.textSize(34.0, UILanguage.English))
    }

    @Test
    fun `a rate the slider stores as a Float keeps its band`() {
        val expected = listOf(0.2f to "Slow", 0.35f to "Slow", 0.4f to "Normal", 0.5f to "Normal", 0.55f to "Normal", 0.6f to "Fast", 0.7f to "Fast")
        for ((stored, word) in expected) {
            val spoken = SliderSpeech.speechRate(stored.toDouble(), UILanguage.English)
            assertTrue(spoken.startsWith("$word,"), "$stored spoke $spoken")
        }
    }

    @Test
    fun `speaker separation is spoken as the way it leans, then its number`() {
        val usual = AppSettings.default.speakerSimilarityThreshold.toDouble()
        assertEquals("More merging, 0.30", SliderSpeech.speakerSeparation(0.3, UILanguage.English))
        assertEquals("Normal, 0.45", SliderSpeech.speakerSeparation(usual, UILanguage.English))
        assertEquals("More separating, 0.80", SliderSpeech.speakerSeparation(0.8, UILanguage.English))
        assertNotEquals(SliderSpeech.speakerSeparation(0.3, UILanguage.Hebrew), SliderSpeech.speakerSeparation(0.3, UILanguage.English))
    }

    @Test
    fun `a separation value saved between steps by an older version keeps its own number`() {
        assertEquals("More separating, 0.47", SliderSpeech.speakerSeparation(0.47, UILanguage.English))
        assertEquals("More merging, 0.42", SliderSpeech.speakerSeparation(0.42, UILanguage.English))
    }

    @Test
    fun `languages that inflect the unit by number say the unit first`() {
        for (language in listOf(UILanguage.Russian, UILanguage.Ukrainian, UILanguage.Arabic)) {
            for (size in listOf(1, 2, 4, 5, 11, 12, 14, 21, 22, 25)) {
                val spoken = SliderSpeech.textSize(size.toDouble(), language)
                assertTrue(spoken.endsWith(" $size"), "$language spoke $spoken")
            }
        }
    }
}
