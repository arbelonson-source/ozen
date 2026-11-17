package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

private val phoneMic = AudioInputDescriptor(uid = "builtin", portName = "iPhone Microphone", portType = AudioPortType.BuiltInMic)
private val usbMic = AudioInputDescriptor(uid = "usb-lav", portName = "USB Lavalier", portType = AudioPortType.Usb)

class CaptionPipelineSoundsOnlyMicrophoneDropTest {
    @Test
    fun `sound alerts alone say when the picked microphone drops, and clear when it returns`() = runTest {
        val audio = FakeAudioCapturer()
        audio.availableInputs = listOf(phoneMic, usbMic)
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = FakeSoundDetector())
        val settings = AppSettings.default
        settings.preferredInputUID = "usb-lav"
        pipeline.listenForSoundsOnly(settings)
        assertEquals("usb-lav", pipeline.selectedInputUID)
        assertNull(pipeline.microphoneDrop.lost)

        audio.selectedInputUID = "builtin"
        audio.simulateRouteChange(listOf(phoneMic))
        assertEquals(usbMic, pipeline.microphoneDrop.lost)

        audio.selectedInputUID = "usb-lav"
        audio.simulateRouteChange(listOf(phoneMic, usbMic))
        assertNull(pipeline.microphoneDrop.lost)
    }
}
