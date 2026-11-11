package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.MicrophoneAsk
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class WalkthroughAnswersTest {
    private fun english(block: () -> Unit) = Localization.withLanguage(UILanguage.English, block)

    @Test
    fun `an approved microphone is said with the reason for the notification question, as on the iPhone`() = english {
        assertEquals(
            listOf("Microphone approved. One more thing: when the phone is in a pocket or locked, Ozen can notify you about a doorbell, an alarm, or your name."),
            WalkthroughAnswers.said(before = null to null, now = MicrophoneAsk.Granted to null),
        )
    }

    @Test
    fun `a refusal is said as the page shows it, blocked only when the system won't ask again`() = english {
        assertEquals(listOf("Without a microphone there are no captions."), WalkthroughAnswers.said(null to null, MicrophoneAsk.AskAgain to null))
        assertEquals(
            listOf("Microphone blocked. Without a microphone there are no captions. You can allow microphone access in the phone’s Settings."),
            WalkthroughAnswers.said(null to null, MicrophoneAsk.OpenSettings to null),
        )
    }

    @Test
    fun `a second refusal that leaves the microphone blocked is said, though the answer is no both times`() = english {
        assertEquals(
            listOf("Microphone blocked. Without a microphone there are no captions. You can allow microphone access in the phone’s Settings."),
            WalkthroughAnswers.said(MicrophoneAsk.AskAgain to null, MicrophoneAsk.OpenSettings to null),
        )
    }

    @Test
    fun `each notification answer is said once it arrives`() = english {
        assertEquals(listOf("Notifications approved"), WalkthroughAnswers.said(MicrophoneAsk.Granted to null, MicrophoneAsk.Granted to true))
        assertEquals(listOf("No notifications. This can be changed in Settings."), WalkthroughAnswers.said(MicrophoneAsk.Granted to null, MicrophoneAsk.Granted to false))
    }

    @Test
    fun `nothing is said for answers already there when the page was shown, or for a question not yet answered`() = english {
        assertEquals(emptyList(), WalkthroughAnswers.said(MicrophoneAsk.Granted to true, MicrophoneAsk.Granted to true))
        assertEquals(emptyList(), WalkthroughAnswers.said(null to null, null to null))
        assertEquals(emptyList(), WalkthroughAnswers.said(MicrophoneAsk.AskAgain to null, MicrophoneAsk.AskAgain to true))
    }
}
