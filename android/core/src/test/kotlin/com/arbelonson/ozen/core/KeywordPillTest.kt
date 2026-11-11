package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeywordPillTest {
    @Test
    fun `normal text size keeps the base time however long the phrase is`() {
        assertEquals(5.0, KeywordPill.visibleSeconds(10, accessibilitySize = false))
        assertEquals(5.0, KeywordPill.visibleSeconds(200, accessibilitySize = false))
        assertEquals(5.0, KeywordPill.visibleSeconds(0, accessibilitySize = true))
    }

    @Test
    fun `a long phrase at an accessibility size stays longer, within a cap, as on the iPhone`() {
        val short = KeywordPill.visibleSeconds(20, accessibilitySize = true)
        val long = KeywordPill.visibleSeconds(60, accessibilitySize = true)
        assertTrue(short > 5)
        assertTrue(long > short)
        assertTrue(KeywordPill.visibleSeconds(5000, accessibilitySize = true) <= 20)
        assertEquals(KeywordPill.visibleSeconds(15, accessibilitySize = true), KeywordPill.visibleSeconds("Dana said: Doctor", accessibilitySize = true) - 0.2, 1e-9)
    }

    @Test
    fun `the text names the speaker when one is known`() {
        val withSpeaker = KeywordPill.text("Doctor", "Dana", UILanguage.English)
        assertTrue(withSpeaker.contains("Dana") && withSpeaker.contains("Doctor"))
        assertEquals("Said: Doctor", KeywordPill.text("Doctor", null, UILanguage.English))
    }

    @Test
    fun `the Hebrew text carries no gendered verb`() {
        assertEquals("דנה — נאמר: רופא", KeywordPill.text("רופא", "דנה", UILanguage.Hebrew))
    }

    @Test
    fun `time left counts down from the first showing and never exceeds the total`() {
        assertEquals(5.0, KeywordPill.secondsLeft(5.0, 0.0))
        assertEquals(2.0, KeywordPill.secondsLeft(5.0, 3.0))
        assertEquals(0.0, KeywordPill.secondsLeft(5.0, 9.0))
        assertEquals(5.0, KeywordPill.secondsLeft(5.0, -4.0))
    }

    @Test
    fun `a longer total after a text size change only adds the extra time`() {
        assertEquals(8.0, KeywordPill.secondsLeft(12.0, 4.0))
        assertTrue(KeywordPill.secondsLeft(12.0, 4.0) > KeywordPill.secondsLeft(5.0, 4.0))
    }
}
