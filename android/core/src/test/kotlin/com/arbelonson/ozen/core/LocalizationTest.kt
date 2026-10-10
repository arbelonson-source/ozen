package com.arbelonson.ozen.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LocalizationTest {
    @Test
    fun `the phone's preferred languages resolve to the first one that's supported, with Hebrew the fallback`() {
        val cases = listOf(
            listOf("he-IL", "en-US") to UILanguage.Hebrew,
            listOf("iw") to UILanguage.Hebrew,
            listOf("en-GB") to UILanguage.English,
            listOf("ru-RU", "he-IL") to UILanguage.Russian,
            listOf("ar-EG") to UILanguage.Arabic,
            listOf("fr-CA", "en-US") to UILanguage.French,
            listOf("zh-Hans-US", "en-US") to UILanguage.ChineseSimplified,
            listOf("zh-Hant-TW") to UILanguage.ChineseSimplified,
            listOf("am-ET", "en-US") to UILanguage.Amharic,
            listOf("es-MX", "en-US") to UILanguage.Spanish,
            listOf("uk-UA", "ru-RU") to UILanguage.Ukrainian,
            listOf("pt-BR", "en-US") to UILanguage.Portuguese,
            listOf("hi-IN", "en-US") to UILanguage.Hindi,
            listOf("da-DK", "de-DE") to UILanguage.German,
            listOf("da-DK") to UILanguage.Hebrew,
            emptyList<String>() to UILanguage.Hebrew,
        )
        for ((preferred, expected) in cases) {
            assertEquals(expected, AppLanguage.System.resolved(preferred), "$preferred")
        }
        assertEquals(UILanguage.Hebrew, AppLanguage.Hebrew.resolved(listOf("en-US")))
        assertEquals(UILanguage.English, AppLanguage.English.resolved(listOf("he-IL")))
        assertEquals(UILanguage.Arabic, AppLanguage.Arabic.resolved(listOf("en-US")))
        assertEquals(UILanguage.Hindi, AppLanguage.Hindi.resolved(emptyList()))
    }

    @Test
    fun `tr gives the version for the language in use`() {
        assertEquals("שלום", tr("שלום", "Hello", UILanguage.Hebrew))
        assertEquals("Hello", tr("שלום", "Hello", UILanguage.English))
        assertEquals("Hello", Localization.withLanguage(UILanguage.English) { tr("שלום", "Hello") })
    }

    @Test
    fun `text put into a placeholder is kept as it is, even when it holds a placeholder itself`() {
        assertEquals("Today at 50%1", tr("%1 בשעה %2", "%1 at %2", listOf("Today", "50%1"), UILanguage.English))
        assertEquals("%2 at 22:30", tr("%1 בשעה %2", "%1 at %2", listOf("%2", "22:30"), UILanguage.English))
        assertEquals("Today a las 50%1", tr("%1 בשעה %2", "%1 at %2", listOf("Today", "50%1"), UILanguage.Spanish))
        assertEquals("היום בשעה %1%2", tr("%1 בשעה %2", "%1 at %2", listOf("היום", "%1%2"), UILanguage.Hebrew))
    }

    @Test
    fun `every language beyond Hebrew and English is looked up in the translation table`() {
        assertEquals("إلغاء", tr("ביטול", "Cancel", UILanguage.Arabic))
        assertEquals("Отмена", tr("ביטול", "Cancel", UILanguage.Russian))
        assertEquals("Annuler", tr("ביטול", "Cancel", UILanguage.French))
        assertEquals("Abbrechen", tr("ביטול", "Cancel", UILanguage.German))
        assertEquals("Cancelar", tr("ביטול", "Cancel", UILanguage.Spanish))
    }

    @Test
    fun `a key missing from a language's table falls back to English, never to an empty string`() {
        val notInAnyTable = "There is definitely no translation for this exact sentence anywhere"
        assertEquals(notInAnyTable, tr("אין תרגום כזה", notInAnyTable, UILanguage.French))
        assertEquals(notInAnyTable, tr("אין תרגום כזה", notInAnyTable, UILanguage.Hindi))
        assertTrue(tr("אין תרגום כזה", notInAnyTable, UILanguage.Amharic).isNotEmpty())
    }

    @Test
    fun `an interpolated string is translated as a template, with the values still substituted in`() {
        val french = tr("הסוללה ב-%1%", "Battery at %1%", listOf("42"), UILanguage.French)
        assertTrue("42" in french)
        assertNotEquals("Battery at 42%", french)
        assertEquals("Battery at 42%", tr("הסוללה ב-%1%", "Battery at %1%", listOf("42"), UILanguage.English))
        assertEquals("הסוללה ב-42%", tr("הסוללה ב-%1%", "Battery at %1%", listOf("42"), UILanguage.Hebrew))
    }

    @Test
    fun `a template used twice repeats the same value both times`() {
        assertEquals("7 of 7, slowest", tr("%1 מתוך %1, הכי איטי", "%1 of %1, slowest", listOf("7"), UILanguage.English))
    }

    @Test
    fun `a placeholder takes the longest number that has a value`() {
        val ten = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
        assertEquals("ten and one", tr("%10 ו-%1", "%10 and %1", ten, UILanguage.English))
        assertEquals("20 minutes", tr("%10 דקות", "%10 minutes", listOf("2"), UILanguage.English))
        assertEquals("42%", tr("%1%", "%1%", listOf("42"), UILanguage.English))
        assertEquals("%0 and %", tr("%0 ו-%", "%0 and %", listOf("5"), UILanguage.English))
    }

    @Test
    fun `a test's own language doesn't leak to others`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals(UILanguage.English, Localization.language)
        }
        assertNull(Localization.overridden)
    }

    @Test
    fun `Hebrew and Arabic read right to left, every other language left to right`() {
        assertTrue(UILanguage.Hebrew.isRightToLeft)
        assertTrue(UILanguage.Arabic.isRightToLeft)
        for (language in UILanguage.entries) {
            if (language != UILanguage.Hebrew && language != UILanguage.Arabic) {
                assertFalse(language.isRightToLeft, "$language should read left to right")
            }
        }
    }

    @Test
    fun `a right-to-left line that opens with an English word or a size stays right to left`() {
        val mark = "‏"
        assertEquals(mark + "VoiceOver מקריא שורות חדשות", tr("VoiceOver מקריא שורות חדשות", "VoiceOver reads new lines aloud", UILanguage.Hebrew))
        assertEquals(mark + "812 MB בשימוש", tr("%1 בשימוש", "%1 in use", listOf("812 MB"), UILanguage.Hebrew))
        assertEquals(mark + "VoiceOver", tr(mark + "VoiceOver", "VoiceOver", UILanguage.Hebrew))
        assertEquals("3 מתוך 5", tr("%1 מתוך %2", "%1 of %2", listOf("3", "5"), UILanguage.Hebrew))
        assertEquals("עוד 1.9 GB", tr("עוד %1", "%1 more", listOf("1.9 GB"), UILanguage.Hebrew))
        for (language in UILanguage.entries.filter { !it.isRightToLeft }) {
            assertFalse(tr("VoiceOver מקריא שורות חדשות", "VoiceOver reads new lines aloud", language).startsWith(mark), "$language")
            assertFalse(tr("%1 בשימוש", "%1 in use", listOf("812 MB"), language).startsWith(mark), "$language")
        }
    }

    @Test
    fun `a left-to-right line that opens with a Hebrew name or word stays left to right`() {
        val mark = "‎"
        assertEquals(mark + "סבתא said: two pills", tr("%1 — נאמר: %2", "%1 said: %2", listOf("סבתא", "two pills"), UILanguage.English))
        assertEquals(
            mark + "“דנה אברהמי” is already in the list.",
            tr("\"%1\" כבר ברשימה.", "“%1” is already in the list.", listOf("דנה אברהמי"), UILanguage.English),
        )
        assertTrue(tr("%1 (%2 הקלטות)", "%1 (%2 recordings)", listOf("סבתא", "2"), UILanguage.Russian).startsWith(mark))
        assertEquals(mark + "סבתא", tr(mark + "%1", mark + "%1", listOf("סבתא"), UILanguage.English))
        assertEquals("Dana said: two pills", tr("%1 — נאמר: %2", "%1 said: %2", listOf("Dana", "two pills"), UILanguage.English))
        assertEquals("סבתא — נאמר: שני כדורים", tr("%1 — נאמר: %2", "%1 said: %2", listOf("סבתא", "שני כדורים"), UILanguage.Hebrew))
        assertEquals("3 of 5", tr("%1 מתוך %2", "%1 of %2", listOf("3", "5"), UILanguage.English))
    }

    @Test
    fun `the voice follows the letters, and the app's language when there are none`() {
        val cases = listOf(
            Triple("שלום, thanks", UILanguage.English, UILanguage.Hebrew),
            Triple("Thank you", UILanguage.Hebrew, UILanguage.English),
            Triple("10:30", UILanguage.Hebrew, UILanguage.Hebrew),
            Triple("10:30", UILanguage.English, UILanguage.English),
            Triple("3 × 4", UILanguage.Hebrew, UILanguage.Hebrew),
            Triple("10 ÷ 2", UILanguage.Hebrew, UILanguage.Hebrew),
            Triple("﻿10:30", UILanguage.Hebrew, UILanguage.Hebrew),
            Triple("Привет", UILanguage.Hebrew, UILanguage.Russian),
            Triple("Дякую", UILanguage.Ukrainian, UILanguage.Ukrainian),
            Triple("Я не зрозуміла", UILanguage.Hebrew, UILanguage.Ukrainian),
            Triple("شكرًا", UILanguage.English, UILanguage.Arabic),
            Triple("አመሰግናለሁ", UILanguage.English, UILanguage.Amharic),
            Triple("धन्यवाद", UILanguage.English, UILanguage.Hindi),
            Triple("谢谢", UILanguage.English, UILanguage.ChineseSimplified),
            Triple("Merci", UILanguage.French, UILanguage.French),
            Triple("Não", UILanguage.Portuguese, UILanguage.Portuguese),
            Triple("Merci", UILanguage.Arabic, UILanguage.English),
            Triple("10:30", UILanguage.German, UILanguage.German),
        )
        for ((text, otherwise, expected) in cases) {
            assertEquals(expected, UILanguage.forSpeaking(text, otherwise), "$text with $otherwise")
        }
    }

    @Test
    fun `dates are written in the app's language, not the phone's, keeping the phone's region`() {
        val hebrew = UILanguage.Hebrew.locale(keepingRegionOf = Locale.forLanguageTag("en-GB"))
        assertEquals("he", hebrew.language)
        assertEquals("GB", hebrew.country)
        val english = UILanguage.English.locale(keepingRegionOf = Locale.forLanguageTag("he-IL"))
        assertEquals("en", english.language)
        assertEquals("IL", english.country)

        val date = Instant.ofEpochSecond(1_791_000_000).atZone(ZoneOffset.UTC)
        val style = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val inHebrew = date.format(style.withLocale(hebrew))
        val inEnglish = date.format(style.withLocale(english))
        assertTrue(inHebrew.any { it in 'א'..'ת' }, inHebrew)
        assertFalse(inEnglish.any { it in 'א'..'ת' }, inEnglish)
        assertTrue("Oct" in inEnglish || "Sep" in inEnglish, inEnglish)
    }

    @Test
    fun `every supported language formats a date in its own way, keeping the phone's region`() {
        val base = Locale.forLanguageTag("en-US")
        val date = Instant.ofEpochSecond(1_791_000_000).atZone(ZoneOffset.UTC)
        for (language in UILanguage.entries) {
            val locale = language.locale(keepingRegionOf = base)
            assertEquals("US", locale.country, "$language")
            assertTrue(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)).isNotEmpty())
        }
        val chinese = UILanguage.ChineseSimplified.locale(keepingRegionOf = base)
        assertEquals("zh", chinese.language)
        assertEquals("Hans", chinese.script)
    }

    @Test
    fun `the Settings picker shows every language by its own name`() {
        val names = UILanguage.entries.associateWith { it.nativeName }
        assertEquals(
            mapOf(
                UILanguage.Hebrew to "עברית", UILanguage.English to "English", UILanguage.Arabic to "العربية",
                UILanguage.Russian to "Русский", UILanguage.Amharic to "አማርኛ", UILanguage.French to "Français",
                UILanguage.Spanish to "Español", UILanguage.Ukrainian to "Українська", UILanguage.German to "Deutsch",
                UILanguage.Portuguese to "Português", UILanguage.ChineseSimplified to "简体中文", UILanguage.Hindi to "हिन्दी",
            ),
            names,
        )
    }

    @Test
    fun `Portuguese is European throughout, like the voice that reads it out`() {
        assertEquals("pt-PT", UILanguage.Portuguese.speechVoiceCode)
        val table = TranslationTable.table(UILanguage.Portuguese)
        assertTrue(table.isNotEmpty())
        val brazilian = setOf("você", "vocês", "celular", "tela", "arquivo", "usuário", "baixar", "contato", "registrar")
        for ((english, portuguese) in table) {
            val words = portuguese.lowercase().split(Regex("[^\\p{L}]+")).toSet()
            for (word in words intersect brazilian) fail("Brazilian '$word' in the Portuguese for: $english")
        }
    }

    @Test
    fun `each language calls the home computer the same thing on every screen`() {
        val words = listOf(
            Triple(UILanguage.Spanish, "computadora", "ordenador"),
            Triple(UILanguage.Arabic, "كمبيوتر", "حاسوب"),
            Triple(UILanguage.Amharic, "ኮምፒዩተ", "ኮምፒውተ"),
        )
        for ((language, used, not) in words) {
            val table = TranslationTable.table(language)
            assertTrue(table.values.any { used in it }, "$language")
            for ((english, text) in table) {
                if (not in text.lowercase()) fail("$language: '$not' in the line for: $english")
            }
        }
    }

    @Test
    fun `every translated language loads, keyed by the iPhone app's language names, with the same lines`() {
        val translated = UILanguage.entries.filter { it != UILanguage.Hebrew && it != UILanguage.English }
        val keys = TranslationTable.table(UILanguage.French).keys
        assertTrue("Cancel" in keys)
        for (language in translated) {
            assertEquals(keys, TranslationTable.table(language).keys, "$language")
        }
    }
}
