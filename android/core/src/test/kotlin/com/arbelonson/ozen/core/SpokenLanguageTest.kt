package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
