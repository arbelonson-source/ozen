package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
