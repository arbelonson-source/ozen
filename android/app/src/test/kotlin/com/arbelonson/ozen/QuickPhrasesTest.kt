package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickPhrasesTest {
    @Test
    fun `a phrase goes at the end of the list as she sees it, once, and a blank one is not added`() {
        val english = AppSettings.defaultQuickPhrases(UILanguage.English)
        val stored = AppSettings.defaultQuickPhrases(UILanguage.Hebrew)
        val added = QuickPhrases.adding("  Thank you very much ", stored, UILanguage.English)
        assertEquals(english + "Thank you very much", added)
        assertEquals(added, QuickPhrases.adding("Thank you very much", added, UILanguage.English))
        assertEquals(added, QuickPhrases.adding("   ", added, UILanguage.English))
        assertEquals(added, QuickPhrases.shown(added, UILanguage.Hebrew))
        assertEquals(english, QuickPhrases.shown(stored, UILanguage.English))
    }

    @Test
    fun `a phrase is deleted from the list as she sees it, and the defaults are known in her language`() {
        val english = AppSettings.defaultQuickPhrases(UILanguage.English)
        val stored = AppSettings.defaultQuickPhrases(UILanguage.Hebrew)
        val fewer = QuickPhrases.removing("Slower, please", stored, UILanguage.English)
        assertEquals(english - "Slower, please", fewer)
        assertTrue(QuickPhrases.isDefault(stored, UILanguage.English))
        assertFalse(QuickPhrases.isDefault(fewer, UILanguage.English))
        assertTrue(QuickPhrases.isDefault(english, UILanguage.English))
    }
}
