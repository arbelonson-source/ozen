package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
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

    @Test
    fun `a picked microphone that drops while sound alerts are still opening is told too`() = runTest {
        val audio = FakeAudioCapturer()
        audio.availableInputs = listOf(phoneMic, usbMic)
        audio.holdPrepare = true
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = FakeSoundDetector())
        val settings = AppSettings.default
        settings.preferredInputUID = "usb-lav"
        audio.selectedInputUID = "usb-lav"
        val opening = launch { pipeline.listenForSoundsOnly(settings) }
        assertTrue(eventually { audio.isHoldingPrepare })
        audio.simulateRouteChange(listOf(phoneMic, usbMic))
        assertFalse(pipeline.isListeningForSoundsOnly)

        audio.selectedInputUID = "builtin"
        audio.simulateRouteChange(listOf(phoneMic))
        assertEquals(usbMic, pipeline.microphoneDrop.lost)

        audio.holdPrepare = false
        audio.releasePrepare()
        opening.join()
    }

    @Test
    fun `an opening that was stopped leaves no notice when the picked microphone drops after`() = runTest {
        val audio = FakeAudioCapturer()
        audio.availableInputs = listOf(phoneMic, usbMic)
        audio.holdPrepare = true
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = FakeSoundDetector())
        val settings = AppSettings.default
        settings.preferredInputUID = "usb-lav"
        audio.selectedInputUID = "usb-lav"
        val opening = launch { pipeline.listenForSoundsOnly(settings) }
        assertTrue(eventually { audio.isHoldingPrepare })
        audio.simulateRouteChange(listOf(phoneMic, usbMic))
        pipeline.stop()
        audio.holdPrepare = false
        audio.releasePrepare()
        opening.join()

        audio.selectedInputUID = "builtin"
        audio.simulateRouteChange(listOf(phoneMic))
        assertNull(pipeline.microphoneDrop.lost)
    }
}
