package com.arbelonson.ozen

import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivacyPolicyLinkTest {
    @Test
    fun `the privacy policy opens in Hebrew for the Hebrew app and in English otherwise, as on the iPhone`() {
        assertEquals("https://ozen.live/he/privacy.html", privacyPolicyUrl(UILanguage.Hebrew))
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            assertEquals("https://ozen.live/privacy.html", privacyPolicyUrl(language), language.name)
        }
    }
}
