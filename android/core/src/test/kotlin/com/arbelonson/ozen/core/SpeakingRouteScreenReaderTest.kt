package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakingRouteScreenReaderTest {
    @Test
    fun `choosing the phone's speaker is announced`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Replies will play on the phone’s speaker", SpeakingRoute.speakerChosenAnnouncement())
    }

    @Test
    fun `where the speaker cannot be chosen the notice says how to fix it`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("To fix this, disconnect the headset or hearing aid.", SpeakingRoute.cannotChooseHint())
    }
}
