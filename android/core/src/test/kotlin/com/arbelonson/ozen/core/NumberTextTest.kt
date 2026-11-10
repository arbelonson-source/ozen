package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberTextTest {
    @Test
    fun `a decimal comma in French, German, Russian, Ukrainian, Spanish and Portuguese, a point elsewhere`() {
        val comma = setOf(UILanguage.French, UILanguage.German, UILanguage.Russian, UILanguage.Ukrainian, UILanguage.Spanish, UILanguage.Portuguese)
        for (language in UILanguage.entries) {
            assertEquals(if (language in comma) "0,55" else "0.55", NumberText.decimal(0.55, 2, language), "$language")
            assertEquals(if (language in comma) "12,3" else "12.3", NumberText.decimal(12.3, 1, language), "$language")
        }
    }

    @Test
    fun `without a language named, the app's language decides`() {
        Localization.withLanguage(UILanguage.German) { assertEquals("1,2", NumberText.decimal(1.24, 1)) }
        Localization.withLanguage(UILanguage.English) { assertEquals("1.3", NumberText.decimal(1.26, 1)) }
    }
}
