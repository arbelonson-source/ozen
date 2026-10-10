package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MicrophoneDropNoticeTest {
    @Test
    fun `a headset going away, or a change while stopped, is not a drop`() {
        val builtIn = AudioInputDescriptor("builtin", "iPhone Microphone", AudioPortType.BuiltInMic)
        val airpods = AudioInputDescriptor("airpods", "AirPods", AudioPortType.Bluetooth)
        val roger = AudioInputDescriptor("roger", "Roger On", AudioPortType.RemoteMic)
        val notice = MicrophoneDropNotice()
        notice.inputChanged(airpods, builtIn, isListening = true)
        assertNull(notice.lost)
        notice.inputChanged(roger, builtIn, isListening = false)
        assertNull(notice.lost)
        notice.inputChanged(roger, builtIn, isListening = true)
        assertEquals(roger, notice.lost)
        notice.inputChanged(builtIn, null, isListening = true)
        assertEquals(roger, notice.lost)
        notice.dismiss()
        assertNull(notice.lost)
    }

    @Test
    fun `with captions stopped, the drop notice does not say they carry on through the phone's microphone`() {
        assertTrue(MicrophoneDropNotice.detail(listening = true).startsWith("הכתוביות ממשיכות"))
        assertFalse(MicrophoneDropNotice.detail(listening = false).contains("ממשיכות"))
        assertTrue(MicrophoneDropNotice.detail(listening = false).contains("המיקרופון של הטלפון"))
    }
}
