package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluralizationTest {
    @Test
    fun `English counts one second and one hour in the singular, more in the plural`() {
        assertTrue(
            TimeUnitWord.second(PluralCategory.One, UILanguage.English) == "second" &&
                TimeUnitWord.second(PluralCategory.Other, UILanguage.English) == "seconds",
        )
        assertTrue(
            TimeUnitWord.hour(PluralCategory.One, UILanguage.English) == "hour" &&
                TimeUnitWord.hour(PluralCategory.Other, UILanguage.English) == "hours",
        )
        assertTrue(
            TimeUnitWord.minute(PluralCategory.One, UILanguage.English) == "minute" &&
                TimeUnitWord.minute(PluralCategory.Other, UILanguage.English) == "minutes",
        )
    }

    @Test
    fun `plural categories fall where each language's rule puts them`() {
        fun categories(language: UILanguage, counts: List<Int>) = counts.map { pluralCategory(it, language) }
        val counts = listOf(0, 1, 2, 3, 5, 11, 12, 21, 22, 25, 100, 101, 111)
        assertEquals(
            listOf("Many", "One", "Few", "Few", "Many", "Many", "Many", "One", "Few", "Many", "Many", "One", "Many"),
            categories(UILanguage.Russian, counts).map { it.name },
        )
        assertEquals(
            listOf("Zero", "One", "Two", "Few", "Few", "Many", "Many", "Many", "Many", "Many", "Other", "Other", "Many"),
            categories(UILanguage.Arabic, counts).map { it.name },
        )
        assertEquals(PluralCategory.One, pluralCategory(0, UILanguage.French))
        assertEquals(PluralCategory.Other, pluralCategory(0, UILanguage.German))
        assertEquals(PluralCategory.Other, pluralCategory(1, UILanguage.ChineseSimplified))
        assertEquals(PluralCategory.Other, pluralCategory(2, UILanguage.English))
    }
}
