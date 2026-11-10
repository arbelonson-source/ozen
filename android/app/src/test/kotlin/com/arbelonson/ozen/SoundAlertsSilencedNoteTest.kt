package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundAlertsSilencedNoteTest {
    @Test
    fun `a group below the chosen level names the choice that silences it`() = Localization.withLanguage(UILanguage.English) {
        assertEquals(
            "You won’t be alerted to these sounds now, because “Emergency only” is chosen above.",
            SoundAlertsText.silencedGroupNote(alertsOn = true, floorName = "Emergency only"),
        )
    }

    @Test
    fun `with sound alerts off every group says it will not alert`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Sound alerts are off, so you won’t be alerted to these sounds.", SoundAlertsText.silencedGroupNote(alertsOn = false, floorName = "Emergency only"))
    }

    @Test
    fun `the Hebrew note quotes the choice in Hebrew`() = Localization.withLanguage(UILanguage.Hebrew) {
        assertTrue(SoundAlertsText.silencedGroupNote(alertsOn = true, floorName = "חירום בלבד").contains("נבחר ״חירום בלבד״"))
    }
}
