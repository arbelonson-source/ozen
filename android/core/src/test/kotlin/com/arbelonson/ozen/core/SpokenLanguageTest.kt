package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpokenLanguageTest {
    @Test
    fun `Hebrew until she chooses, as before there was a choice`() {
        assertEquals(UILanguage.Hebrew, AppSettings.default.spokenLanguage)
    }

    @Test
    fun `each language chosen reaches the engines as Whisper's code for it, and reads back as chosen`() {
        val codes = mapOf(
            UILanguage.Hebrew to "he", UILanguage.English to "en", UILanguage.Arabic to "ar", UILanguage.Russian to "ru",
            UILanguage.Amharic to "am", UILanguage.French to "fr", UILanguage.Spanish to "es", UILanguage.Ukrainian to "uk",
            UILanguage.German to "de", UILanguage.Portuguese to "pt", UILanguage.ChineseSimplified to "zh", UILanguage.Hindi to "hi",
        )
        assertEquals(UILanguage.entries.toSet(), codes.keys)
        for ((language, code) in codes) {
            val settings = AppSettings.default
            settings.spokenLanguage = language
            assertEquals(code, settings.languageCode)
            assertEquals(language, settings.spokenLanguage)
        }
    }

    @Test
    fun `each is named as it is spoken, Chinese as 中文 and not by the simplified writing the menus name, the rest as in the menus`() {
        assertEquals("中文", UILanguage.ChineseSimplified.spokenName)
        for (language in UILanguage.entries.filter { it != UILanguage.ChineseSimplified }) {
            assertEquals(language.nativeName, language.spokenName)
        }
    }

    @Test
    fun `a saved code with a region reads as its language, and one no listed language has, or Hebrew's old code, reads as Hebrew`() {
        val settings = AppSettings.default
        settings.languageCode = "en-GB"
        assertEquals(UILanguage.English, settings.spokenLanguage)
        settings.languageCode = "xx"
        assertEquals(UILanguage.Hebrew, settings.spokenLanguage)
        settings.languageCode = "iw"
        assertEquals(UILanguage.Hebrew, settings.spokenLanguage)
    }

    @Test
    fun `Hebrew keeps Ozen's Hebrew model, and every other language is recommended the general Turbo, which isn't trained for Hebrew alone`() {
        assertEquals(WhisperModelCatalog.RECOMMENDED_VARIANT, WhisperModelCatalog.recommendedVariant(UILanguage.Hebrew))
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            assertEquals("large-v3-v20240930_626MB", WhisperModelCatalog.recommendedVariant(language))
        }
        assertEquals(false, WhisperModelCatalog.option("large-v3-v20240930_626MB")?.isHebrewOnly)
        assertEquals(listOf("ozen-turbo-hebrew-a3-8bit", "ivrit-large-v3-turbo-8bit"), WhisperModelCatalog.options.filter { it.isHebrewOnly }.map { it.variant })
    }

    @Test
    fun `for any other language a model trained for Hebrew alone is the weaker one, and the general ones are left as chosen`() {
        val settings = AppSettings.default
        settings.spokenLanguage = UILanguage.English
        assertEquals("large-v3-v20240930_626MB", settings.recommendedWhisperVariant)
        assertTrue(settings.runsWeakerModel)
        settings.whisperModelVariant = "ivrit-large-v3-turbo-8bit"
        assertTrue(settings.runsWeakerModel)
        for (general in listOf("large-v3-v20240930_626MB", "small", "large-v3")) {
            settings.whisperModelVariant = general
            assertFalse(settings.runsWeakerModel, general)
        }
        settings.spokenLanguage = UILanguage.Hebrew
        assertEquals(WhisperModelCatalog.RECOMMENDED_VARIANT, settings.recommendedWhisperVariant)
        settings.whisperModelVariant = "small"
        assertTrue(settings.runsWeakerModel)
        settings.whisperModelVariant = WhisperModelCatalog.RECOMMENDED_VARIANT
        assertFalse(settings.runsWeakerModel)
    }

    @Test
    fun `the model screen lists the language's recommended model first, the other general ones next, and for any language but Hebrew the Hebrew-only ones last`() {
        assertEquals(WhisperModelCatalog.listed, WhisperModelCatalog.listed(UILanguage.Hebrew))
        val english = WhisperModelCatalog.listed(UILanguage.English).map { it.variant }
        assertEquals(WhisperModelCatalog.GENERAL_VARIANT, english.first())
        assertEquals(listOf("ozen-turbo-hebrew-a3-8bit", "ivrit-large-v3-turbo-8bit"), english.takeLast(2))
        assertEquals(WhisperModelCatalog.options.map { it.variant }.sorted(), english.sorted())
    }

    @Test
    fun `the suggested names and words are offered as said in the spoken language, Hebrew's own or what each means in it`() {
        assertEquals(AlertSuggestions.names, AlertSuggestions.offeredNames(UILanguage.Hebrew))
        assertEquals(AlertSuggestions.words, AlertSuggestions.offeredWords(UILanguage.Hebrew))
        assertEquals(listOf("Grandma", "Mom"), AlertSuggestions.offeredNames(UILanguage.English))
        assertEquals(listOf("Grandma", "Mom", "Medicine", "Doctor"), AlertSuggestions.offeredWords(UILanguage.English))
        assertEquals(listOf("Бабушка", "Мама"), AlertSuggestions.offeredNames(UILanguage.Russian))
    }

    @Test
    fun `in English a lone you is only dropped when the model was unsure, like thank you, and in any other language it is Whisper's filler for silence`() {
        val sure = WhisperSegmentSummary(text = "You.", noSpeechProb = 0.05f, avgLogprob = -0.2f, compressionRatio = 1f)
        val unsure = WhisperSegmentSummary(text = "You.", noSpeechProb = 0.5f, avgLogprob = -0.2f, compressionRatio = 1f)
        val english = WhisperResultFilter().forSpeech("en")
        assertTrue(english.accepts(sure))
        assertFalse(english.accepts(unsure))
        assertFalse(WhisperResultFilter().forSpeech("he").accepts(sure))
        assertFalse(WhisperResultFilter().forSpeech("ru").accepts(sure))
        assertFalse(english.accepts(WhisperSegmentSummary(text = "Thanks for watching!", noSpeechProb = 0.05f, avgLogprob = -0.2f, compressionRatio = 1f)))
    }

    @Test
    fun `the phone's own model file is Ozen's Hebrew one for Hebrew and whisper cpp's general Turbo for every other language`() {
        assertEquals("ggml-ozen-a3-q5_0.bin", WhisperModelCatalog.phoneModelFile(UILanguage.Hebrew))
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            assertEquals("ggml-large-v3-turbo-q5_0.bin", WhisperModelCatalog.phoneModelFile(language))
        }
    }
}
