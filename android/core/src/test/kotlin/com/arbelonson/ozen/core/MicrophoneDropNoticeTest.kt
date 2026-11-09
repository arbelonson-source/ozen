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

    @Test
    fun `a Bluetooth microphone she picked that is lost while listening is warned about, one nobody picked is not`() {
        val builtIn = AudioInputDescriptor("builtin", "iPhone Microphone", AudioPortType.BuiltInMic)
        val lapel = AudioInputDescriptor("bluetooth:aa", "Lapel Pro", AudioPortType.Bluetooth)
        val earbuds = AudioInputDescriptor("bluetooth:bb", "Earbuds", AudioPortType.Bluetooth)
        val notice = MicrophoneDropNotice()
        notice.inputChanged(earbuds, builtIn, isListening = true, preferredUID = lapel.uid)
        assertNull(notice.lost)
        notice.inputChanged(lapel, builtIn, isListening = false, preferredUID = lapel.uid)
        assertNull(notice.lost)
        notice.inputChanged(lapel, builtIn, isListening = true, preferredUID = lapel.uid)
        assertEquals(lapel, notice.lost)
    }

    @Test
    fun `wired and USB microphones are warned about whether or not they were picked`() {
        val builtIn = AudioInputDescriptor("builtin", "iPhone Microphone", AudioPortType.BuiltInMic)
        val wired = AudioInputDescriptor("wired:", "Headset", AudioPortType.Wired)
        val usb = AudioInputDescriptor("usb:1", "USB Lavalier", AudioPortType.Usb)
        val notice = MicrophoneDropNotice()
        notice.inputChanged(wired, builtIn, isListening = true, preferredUID = "builtin")
        assertEquals(wired, notice.lost)
        notice.dismiss()
        notice.inputChanged(usb, builtIn, isListening = true, preferredUID = null)
        assertEquals(usb, notice.lost)
    }

    @Test
    fun `a hearing aid that was picked and is lost while listening is warned about, one nobody picked is not`() {
        val builtIn = AudioInputDescriptor("builtin", "Phone Microphone", AudioPortType.BuiltInMic)
        val hearingAid = AudioInputDescriptor("hearing:aa", "Phonak Audeo", AudioPortType.HearingAid)
        val otherAid = AudioInputDescriptor("hearing:bb", "Oticon More", AudioPortType.HearingAid)
        val notice = MicrophoneDropNotice()
        notice.inputChanged(otherAid, builtIn, isListening = true, preferredUID = hearingAid.uid)
        assertNull(notice.lost)
        notice.inputChanged(hearingAid, builtIn, isListening = false, preferredUID = hearingAid.uid)
        assertNull(notice.lost)
        notice.inputChanged(hearingAid, builtIn, isListening = true, preferredUID = null)
        assertNull(notice.lost)
        notice.inputChanged(hearingAid, builtIn, isListening = true, preferredUID = hearingAid.uid)
        assertEquals(hearingAid, notice.lost)
    }
}
