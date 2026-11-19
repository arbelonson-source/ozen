package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeakingVolumeTest {
    private val zero = "The phone’s media volume is at zero, so nobody will hear these phrases."
    private val low = "The phone’s media volume is very low, so nobody may hear these phrases."

    @Test
    fun `at zero nobody will hear the phrases`() = Localization.withLanguage(UILanguage.English) {
        assertEquals(zero, SpeakingVolume.notice(0, 15))
        assertEquals(zero, SpeakingVolume.notice(0, 100))
    }

    @Test
    fun `the lowest step above zero is too quiet to hear and says so`() = Localization.withLanguage(UILanguage.English) {
        assertEquals(low, SpeakingVolume.notice(1, 15))
        assertEquals(low, SpeakingVolume.notice(1, 25))
        assertEquals(low, SpeakingVolume.notice(6, 100))
    }

    @Test
    fun `a level just above the threshold or more says nothing`() {
        assertNull(SpeakingVolume.notice(2, 15))
        assertNull(SpeakingVolume.notice(1, 7))
        assertNull(SpeakingVolume.notice(8, 100))
        assertNull(SpeakingVolume.notice(15, 15))
    }

    @Test
    fun `a phone that reports no range says nothing about a level above zero`() {
        assertNull(SpeakingVolume.notice(1, 0))
    }
}
