package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeArticleTest {
    @Test
    fun `Spanish says a la 1_05 but a las 13_05, 10_00 and 11_30`() {
        assertEquals("hoy a la 1:05", TimeArticle.agreeing("hoy a las 1:05", "1:05", UILanguage.Spanish))
        assertEquals("hacia la 1:05 p. m. y", TimeArticle.agreeing("hacia las 1:05 p. m. y", "1:05 p. m.", UILanguage.Spanish))
        assertEquals("hoy a las 13:05", TimeArticle.agreeing("hoy a las 13:05", "13:05", UILanguage.Spanish))
        assertEquals("hoy a las 10:00", TimeArticle.agreeing("hoy a las 10:00", "10:00", UILanguage.Spanish))
        assertEquals("hoy a las 11:30", TimeArticle.agreeing("hoy a las 11:30", "11:30", UILanguage.Spanish))
    }

    @Test
    fun `Portuguese says a 01_05 and por volta da 1_05 but as 21_05`() {
        assertEquals("hoje à 01:05", TimeArticle.agreeing("hoje às 01:05", "01:05", UILanguage.Portuguese))
        assertEquals("por volta da 1:05 e", TimeArticle.agreeing("por volta das 1:05 e", "1:05", UILanguage.Portuguese))
        assertEquals("hoje às 21:05", TimeArticle.agreeing("hoje às 21:05", "21:05", UILanguage.Portuguese))
    }

    @Test
    fun `other languages are left as they are`() {
        for (language in UILanguage.entries.filter { it != UILanguage.Spanish && it != UILanguage.Portuguese }) {
            assertEquals("las 1:05 às 1:05 das 1:05", TimeArticle.agreeing("las 1:05 às 1:05 das 1:05", "1:05", language), "$language")
        }
    }
}
