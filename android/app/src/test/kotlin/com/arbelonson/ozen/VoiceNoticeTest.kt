package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceNoticeTest {
    private val noHebrew = "No Hebrew voice is installed."
    private val noFrench = "No voice for Français is installed on this phone, so these phrases can’t be read aloud."

    @Test
    fun `nothing is said before the phone has answered which voices it has`() = Localization.withLanguage(UILanguage.English) {
        assertNull(VoiceNotice.forSettings(UILanguage.Hebrew, installed = null))
        assertEquals(emptyList(), VoiceNotice.forSay(UILanguage.French, installed = null))
    }

    @Test
    fun `settings says when the app's language has no voice, and the hebrew one only when the app is in hebrew`() = Localization.withLanguage(UILanguage.English) {
        assertEquals(noFrench, VoiceNotice.forSettings(UILanguage.French, setOf(UILanguage.English)))
        assertEquals(noHebrew, VoiceNotice.forSettings(UILanguage.Hebrew, setOf(UILanguage.English)))
        assertNull(VoiceNotice.forSettings(UILanguage.English, emptySet()))
        assertNull(VoiceNotice.forSettings(UILanguage.French, setOf(UILanguage.French)))
    }

    @Test
    fun `the say screen says both, since a hebrew phrase needs the hebrew voice whatever the app's language`() = Localization.withLanguage(UILanguage.English) {
        assertEquals(listOf(noFrench, noHebrew), VoiceNotice.forSay(UILanguage.French, setOf(UILanguage.English)))
        assertEquals(listOf(noHebrew), VoiceNotice.forSay(UILanguage.English, setOf(UILanguage.English)))
        assertEquals(emptyList(), VoiceNotice.forSay(UILanguage.Hebrew, setOf(UILanguage.Hebrew)))
    }
}
