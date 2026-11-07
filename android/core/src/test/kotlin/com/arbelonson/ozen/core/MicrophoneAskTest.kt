package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MicrophoneAskTest {
    @Test
    fun `an allowed microphone needs no question whatever the system would do`() {
        assertEquals(MicrophoneAsk.Granted, MicrophoneAsk.next(granted = true, canAskAgain = false))
        assertEquals(MicrophoneAsk.Granted, MicrophoneAsk.next(granted = true, canAskAgain = true))
    }

    @Test
    fun `one refusal the system will still ask about is asked again, not sent to Settings`() {
        assertEquals(MicrophoneAsk.AskAgain, MicrophoneAsk.next(granted = false, canAskAgain = true))
    }

    @Test
    fun `a refusal the system will not ask about again goes to Settings`() {
        assertEquals(MicrophoneAsk.OpenSettings, MicrophoneAsk.next(granted = false, canAskAgain = false))
    }
}
