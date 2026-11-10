package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PointerAccessTest {
    @Test
    fun switchAccessVoiceAccessAndUniversalSwitchPressButtonsWithoutATouch() {
        val pointing = listOf(
            "com.google.android.marvin.talkback/com.google.android.accessibility.switchaccess.SwitchAccessService",
            "com.google.android.apps.accessibility.voiceaccess/.JustSpeakService",
            "com.samsung.accessibility/.universalswitch.UniversalSwitchService",
        )
        assertEquals(listOf(true, true, true), pointing.map { ScreenReader.pointsWithoutTouch(listOf(it)) })
    }

    @Test
    fun aScreenReaderOrAnUnrelatedServiceDoesNotCount() {
        val others = listOf(
            "com.google.android.marvin.talkback/.TalkBackService",
            "com.x8bit.bitwarden/com.x8bit.bitwarden.Accessibility.AccessibilityService",
            "com.google.android.marvin.talkback/com.google.android.accessibility.accessibilitymenu.AccessibilityMenuService",
        )
        assertFalse(ScreenReader.pointsWithoutTouch(others))
        assertFalse(ScreenReader.pointsWithoutTouch(emptyList()))
    }
}
