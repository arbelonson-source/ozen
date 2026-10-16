package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OnboardingPrivacyLineTest {
    private val english = "By default it all happens on the phone."

    @Test
    fun `the default line has a real translation in every language beyond Hebrew and English`() {
        for (language in UILanguage.values()) {
            if (language == UILanguage.Hebrew || language == UILanguage.English) continue
            val line = TranslationTable.lookup(english, language)
            assertNotNull(line, language.name)
            assertTrue(line.isNotEmpty(), language.name)
            assertNotEquals(english, line, language.name)
        }
    }

    @Test
    fun `the old blanket claim is in no translation table`() {
        for (language in UILanguage.values()) {
            val table = TranslationTable.table(language)
            assertTrue(table.keys.none { it.contains("Nothing is sent to the internet") }, language.name)
        }
    }
}
