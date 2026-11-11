package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class WalkthroughAnswersTest {
    private fun english(block: () -> Unit) = Localization.withLanguage(UILanguage.English, block)

    @Test
    fun `an approved microphone is said with the reason for the notification question, as on the iPhone`() = english {
        assertEquals(
            listOf("Microphone approved. One more thing: when the phone is in a pocket or locked, Ozen can notify you about a doorbell, an alarm, or your name."),
            WalkthroughAnswers.said(before = null to null, now = true to null, canAskAgain = true),
        )
    }

    @Test
    fun `a refusal is said as the page shows it, blocked only when the system won't ask again`() = english {
        assertEquals(listOf("Without a microphone there are no captions."), WalkthroughAnswers.said(null to null, false to null, canAskAgain = true))
        assertEquals(
            listOf("Microphone blocked. Without a microphone there are no captions. You can allow microphone access in the phone’s Settings."),
            WalkthroughAnswers.said(null to null, false to null, canAskAgain = false),
        )
    }

    @Test
    fun `each notification answer is said once it arrives`() = english {
        assertEquals(listOf("Notifications approved"), WalkthroughAnswers.said(true to null, true to true, canAskAgain = true))
        assertEquals(listOf("No notifications. This can be changed in Settings."), WalkthroughAnswers.said(true to null, true to false, canAskAgain = true))
    }

    @Test
    fun `nothing is said for answers already there when the page was shown, or for a question not yet answered`() = english {
        assertEquals(emptyList(), WalkthroughAnswers.said(true to true, true to true, canAskAgain = true))
        assertEquals(emptyList(), WalkthroughAnswers.said(null to null, null to null, canAskAgain = true))
        assertEquals(emptyList(), WalkthroughAnswers.said(false to null, false to true, canAskAgain = true))
    }
}
