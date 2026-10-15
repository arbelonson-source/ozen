package com.arbelonson.ozen

import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SoundAlertsSafetyNoteTest {
    @Test
    fun `the page says Ozen is a helper and not a certified alarm`() {
        val english = SoundAlertsText.safetyNote(UILanguage.English)
        assertTrue(english.startsWith("Ozen is a helper, not a certified alarm."))
        assertTrue(english.contains("keep your smoke alarms"))
        assertTrue(english.contains("official emergency alerts"))
    }

    @Test
    fun `the note is translated into every language and does not fall back to Hebrew`() {
        val hebrew = SoundAlertsText.safetyNote(UILanguage.Hebrew)
        val english = SoundAlertsText.safetyNote(UILanguage.English)
        for (language in UILanguage.entries) {
            if (language == UILanguage.Hebrew) continue
            val shown = SoundAlertsText.safetyNote(language).trimStart('\u200E', '\u200F')
            assertNotEquals(hebrew, shown, "$language fell back to Hebrew")
            if (language != UILanguage.English) assertNotEquals(english, shown, "$language has no translation")
        }
    }
}
