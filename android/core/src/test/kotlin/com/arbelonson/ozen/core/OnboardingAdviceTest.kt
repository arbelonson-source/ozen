package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingAdviceTest {
    @Test
    fun `the Settings button shows only after notifications were refused`() {
        assertTrue(OnboardingNotificationAdvice.showsSettingsButton(false))
        assertFalse(OnboardingNotificationAdvice.showsSettingsButton(true))
        assertFalse(OnboardingNotificationAdvice.showsSettingsButton(null))
    }

    @Test
    fun `a refused answer survives a rebuild until the phone says yes`() {
        assertEquals(false, OnboardingNotificationAdvice.keptAcrossRebuild(allowedNow = false, remembered = false))
        assertEquals(true, OnboardingNotificationAdvice.keptAcrossRebuild(allowedNow = true, remembered = false))
        assertEquals(null, OnboardingNotificationAdvice.keptAcrossRebuild(allowedNow = false, remembered = null))
        assertEquals(null, OnboardingNotificationAdvice.keptAcrossRebuild(allowedNow = false, remembered = true))
        assertEquals(false, OnboardingMicrophoneAdvice.keptAcrossRebuild(grantedNow = false, remembered = false))
        assertEquals(true, OnboardingMicrophoneAdvice.keptAcrossRebuild(grantedNow = true, remembered = false))
        assertEquals(null, OnboardingMicrophoneAdvice.keptAcrossRebuild(grantedNow = false, remembered = null))
    }

    @Test
    fun `the Wi-Fi sentence is shown only while the chosen model is not on the phone`() {
        assertTrue(OnboardingModelNote.showsWifiNote("small.bin", emptySet(), mobileDataAllowed = false))
        assertTrue(OnboardingModelNote.showsWifiNote("small.bin", setOf("tiny.bin"), mobileDataAllowed = false))
        assertFalse(OnboardingModelNote.showsWifiNote("small.bin", setOf("small.bin"), mobileDataAllowed = false))
        assertFalse(OnboardingModelNote.showsWifiNote("small.bin", emptySet(), mobileDataAllowed = true))
    }
}
