package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidInputChoiceTest {
    private val builtIn = AudioInputDescriptor("builtInMic:bottom", "Phone microphone", AudioPortType.BuiltInMic)
    private val usb = AudioInputDescriptor("usb:card=1;device=0", "USB-C Lavalier", AudioPortType.Usb)
    private val wired = AudioInputDescriptor("wired:", "Wired headset", AudioPortType.Wired)
    private val bluetooth = AudioInputDescriptor("bluetooth:AA:BB", "Earbuds", AudioPortType.Bluetooth)
    private val hearingAid = AudioInputDescriptor("hearingAid:CC:DD", "Hearing aids", AudioPortType.HearingAid)

    @Test
    fun `with nothing picked, a USB microphone plugged in at launch is used`() {
        assertEquals(usb.uid, AndroidInputChoice.resolve(listOf(builtIn, usb), preferredUID = null, lastSelectedUID = null))
    }

    @Test
    fun `with nothing picked, a USB microphone plugged in during captions takes over from the phone's own`() {
        assertEquals(usb.uid, AndroidInputChoice.resolve(listOf(builtIn, usb), preferredUID = null, lastSelectedUID = builtIn.uid))
    }

    @Test
    fun `with nothing picked, a wired microphone is used`() {
        assertEquals(wired.uid, AndroidInputChoice.resolve(listOf(builtIn, wired), preferredUID = null, lastSelectedUID = builtIn.uid))
    }

    @Test
    fun `a wired microphone comes before a USB one, as Android orders them itself`() {
        assertEquals(wired.uid, AndroidInputChoice.systemDefault(listOf(builtIn, usb, wired)))
    }

    @Test
    fun `the phone's own microphone stays when it is the one picked`() {
        assertEquals(builtIn.uid, AndroidInputChoice.resolve(listOf(builtIn, usb), preferredUID = builtIn.uid, lastSelectedUID = usb.uid))
    }

    @Test
    fun `unplugging the USB microphone goes back to the phone's own`() {
        assertEquals(builtIn.uid, AndroidInputChoice.resolve(listOf(builtIn), preferredUID = null, lastSelectedUID = usb.uid))
    }

    @Test
    fun `a picked USB microphone comes back when it is plugged in again, ahead of a wired one`() {
        assertEquals(wired.uid, AndroidInputChoice.resolve(listOf(builtIn, wired), preferredUID = usb.uid, lastSelectedUID = usb.uid))
        assertEquals(usb.uid, AndroidInputChoice.resolve(listOf(builtIn, wired, usb), preferredUID = usb.uid, lastSelectedUID = wired.uid))
    }

    @Test
    fun `Bluetooth earbuds and hearing aids are not chosen by themselves`() {
        assertNull(AndroidInputChoice.systemDefault(listOf(builtIn, bluetooth)))
        assertNull(AndroidInputChoice.systemDefault(listOf(builtIn, hearingAid)))
        assertNull(AndroidInputChoice.systemDefault(listOf(builtIn)))
        assertEquals(builtIn.uid, AndroidInputChoice.resolve(listOf(builtIn, bluetooth, hearingAid), preferredUID = null, lastSelectedUID = null))
    }
}
