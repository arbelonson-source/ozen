package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertTrue

class HomeServerGuideTest {
    @Test
    fun `the setup step says its questions are in English and what to answer, as on the iPhone`() = Localization.withLanguage(UILanguage.English) {
        val setup = HomeServerGuide.steps[2].second
        assertTrue(setup.startsWith("It first checks that the graphics card is good enough"), setup)
        assertTrue(setup.endsWith(" Its questions are in English: at home, answer “Yes” to each. To the last one, “No” is fine too: running the setup again later adds it."), setup)
    }

    @Test
    fun `in French the answer to give is named in French and in the setup's English`() = Localization.withLanguage(UILanguage.French) {
        val setup = HomeServerGuide.steps[2].second
        assertTrue(setup.endsWith("répondez «\u00A0Oui\u00A0» (Yes) à chacune. À la dernière, «\u00A0Non\u00A0» (No) convient aussi\u00A0: relancer l’installation plus tard permet de l’ajouter."), setup)
    }
}
