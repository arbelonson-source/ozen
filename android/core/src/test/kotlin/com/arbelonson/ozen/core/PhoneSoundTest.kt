package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val mic = AudioInputDescriptor("builtin", "Phone microphone", AudioPortType.BuiltInMic)
private val lapel = AudioInputDescriptor("usb-lav", "USB Lavalier", AudioPortType.Usb)

class PhoneSoundTest {
    @Test
    fun `the phone's own sound is listed after the real microphones from Android 10`() {
        Localization.withLanguage(UILanguage.English) {
            val listed = PhoneSound.inputs(listOf(mic, lapel), sdk = 29)
            assertEquals(listOf("builtin", "usb-lav", PhoneSound.UID), listed.map { it.uid })
            assertEquals(AudioPortType.Other, listed.last().portType)
            assertEquals("Sound playing on this phone", listed.last().portName)
            assertEquals(listed, PhoneSound.inputs(listOf(mic, lapel), sdk = 34))
        }
    }

    @Test
    fun `it is never listed on Android 9`() {
        assertEquals(listOf(mic, lapel), PhoneSound.inputs(listOf(mic, lapel), sdk = 28))
    }

    @Test
    fun `choosing it never writes the saved microphone`() = runTest {
        val audio = FakeAudioCapturer()
        audio.availableInputs = listOf(mic, PhoneSound.entry)
        val (pipeline, _, _) = makePipeline(audio = audio)
        val settings = AppSettings.default
        settings.preferredInputUID = "builtin"
        pipeline.start(settings)

        assertTrue(pipeline.selectInput(PhoneSound.UID))

        assertEquals(PhoneSound.UID, pipeline.selectedInputUID)
        assertEquals("builtin", pipeline.activeSettings?.preferredInputUID)
        assertTrue(PhoneSound.isPhoneSound(PhoneSound.UID))
        assertFalse(PhoneSound.isPhoneSound("builtin"))
    }

    @Test
    fun `a stopped projection goes back to the microphone in use, once`() {
        val listening = PhoneSoundListening()
        listening.begin("usb-lav")
        assertTrue(listening.active)

        val stop = listening.projectionStopped()

        assertEquals("usb-lav", stop?.returnTo)
        assertFalse(listening.active)
        assertNull(listening.projectionStopped())
    }

    @Test
    fun `captions stopping leaves phone sound quietly and a later projection stop says nothing`() {
        val listening = PhoneSoundListening()
        listening.begin("builtin")

        assertEquals("builtin", listening.leave())

        assertFalse(listening.active)
        assertNull(listening.projectionStopped())
        assertNull(listening.leave())
    }

    @Test
    fun `the microphone being silenced is ignored on phone sound and heeded otherwise`() {
        val listening = PhoneSoundListening()
        assertTrue(listening.heedsMicrophoneSilence)
        listening.begin("builtin")
        assertFalse(listening.heedsMicrophoneSilence)
        listening.projectionStopped()
        assertTrue(listening.heedsMicrophoneSilence)
    }
}
