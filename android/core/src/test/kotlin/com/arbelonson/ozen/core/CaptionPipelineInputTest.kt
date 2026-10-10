package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val builtIn = AudioInputDescriptor(uid = "builtin", portName = "iPhone Microphone", portType = AudioPortType.BuiltInMic)
private val lapel = AudioInputDescriptor(uid = "usb-lav", portName = "USB Lavalier", portType = AudioPortType.Usb)

private fun lapelAudio(): FakeAudioCapturer {
    val audio = FakeAudioCapturer()
    audio.availableInputs = listOf(builtIn, lapel)
    return audio
}

private fun lapelSettings(): AppSettings {
    val settings = AppSettings.default
    settings.preferredInputUID = "usb-lav"
    return settings
}

class CaptionPipelineInputTest {
    @Test
    fun `selecting an input updates the selection and counts the change`() = runTest {
        val audio = FakeAudioCapturer()
        audio.availableInputs = audio.availableInputs + AudioInputDescriptor(uid = "airpods", portName = "AirPods", portType = AudioPortType.Bluetooth)
        val (pipeline, _, _) = makePipeline(audio = audio)
        pipeline.start(AppSettings.default)

        assertTrue(pipeline.selectInput("airpods"))

        assertEquals("airpods", pipeline.selectedInputUID)
        assertEquals(1, pipeline.stats.inputChanges)
    }

    @Test
    fun `a failed selection keeps the previous input and doesn't fail the pipeline`() = runTest {
        val (pipeline, _, _) = makePipeline()
        pipeline.start(AppSettings.default)

        assertFalse(pipeline.selectInput("ghost"))

        assertEquals("builtin", pipeline.selectedInputUID)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a lapel microphone dropping mid-conversation says so, its return clears it`() = runTest {
        val audio = lapelAudio()
        val (pipeline, _, _) = makePipeline(audio = audio)
        pipeline.start(lapelSettings())
        assertEquals("usb-lav", pipeline.selectedInputUID)
        assertNull(pipeline.microphoneDrop.lost)

        audio.selectedInputUID = "builtin"
        audio.simulateRouteChange(listOf(builtIn))
        assertEquals(lapel, pipeline.microphoneDrop.lost)
        assertEquals(true, pipeline.microphoneDrop.title?.contains("USB Lavalier"))

        audio.selectedInputUID = "usb-lav"
        audio.simulateRouteChange(listOf(builtIn, lapel))
        assertNull(pipeline.microphoneDrop.lost)
    }

    @Test
    fun `the notice of a dropped microphone goes away when she closes it`() = runTest {
        val audio = lapelAudio()
        val (pipeline, _, _) = makePipeline(audio = audio)
        pipeline.start(lapelSettings())
        audio.selectedInputUID = "builtin"
        audio.simulateRouteChange(listOf(builtIn))
        assertEquals(lapel, pipeline.microphoneDrop.lost)

        pipeline.dismissMicrophoneDrop()
        assertNull(pipeline.microphoneDrop.lost)
        assertTrue(pipeline.phase.isListening)
    }

    @Test
    fun `stopping captions takes down the notice of a dropped microphone`() = runTest {
        val audio = lapelAudio()
        val (pipeline, _, _) = makePipeline(audio = audio)
        pipeline.start(lapelSettings())
        audio.selectedInputUID = "builtin"
        audio.simulateRouteChange(listOf(builtIn))
        assertEquals(lapel, pipeline.microphoneDrop.lost)

        pipeline.stop()
        assertNull(pipeline.microphoneDrop.lost)
    }

    @Test
    fun `a lapel microphone unplugged while captions are paused is said when they resume on the phone's own`() = runTest {
        val audio = lapelAudio()
        val (pipeline, _, _) = makePipeline(audio = audio)
        pipeline.start(lapelSettings())
        assertEquals("usb-lav", pipeline.selectedInputUID)

        pipeline.pause()
        audio.selectedInputUID = "builtin"
        audio.simulateRouteChange(listOf(builtIn))
        pipeline.resume()
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals("builtin", pipeline.selectedInputUID)
        assertEquals(lapel, pipeline.microphoneDrop.lost)
    }

    @Test
    fun `a lapel microphone unplugged after captions were stopped is not a drop`() = runTest {
        val audio = lapelAudio()
        val (pipeline, _, _) = makePipeline(audio = audio)
        pipeline.start(lapelSettings())
        assertEquals("usb-lav", pipeline.selectedInputUID)

        pipeline.stop()
        audio.selectedInputUID = "builtin"
        audio.simulateRouteChange(listOf(builtIn))
        assertNull(pipeline.microphoneDrop.lost)
    }

    @Test
    fun `choosing the phone's own microphone in the picker is not a drop`() = runTest {
        val audio = lapelAudio()
        val (pipeline, _, _) = makePipeline(audio = audio)
        pipeline.start(lapelSettings())
        assertEquals("usb-lav", pipeline.selectedInputUID)

        assertTrue(pipeline.selectInput("builtin"))
        assertNull(pipeline.microphoneDrop.lost)
    }

    @Test
    fun `a headset going away, or a change while stopped, is not a drop`() {
        val airpods = AudioInputDescriptor(uid = "airpods", portName = "AirPods", portType = AudioPortType.Bluetooth)
        val roger = AudioInputDescriptor(uid = "roger", portName = "Roger On", portType = AudioPortType.RemoteMic)
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
    fun `a system route change refreshes the list without restarting`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)

        audio.simulateRouteChange(listOf(builtIn, lapel))

        assertEquals(listOf("builtin", "usb-lav"), pipeline.availableInputs.map { it.uid })
        assertEquals(1, pipeline.stats.inputChanges)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(1, audio.calls.count { it == "startCapture" })
    }
}

class CaptionPipelineCloudServiceTest {
    @Test
    fun `switching the cloud service makes an engine for it instead of reusing the last service's`() = runTest {
        val made = ArrayList<AppSettings>()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                made.add(settings.copy())
                FakeEngine()
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.Cloud
        pipeline.start(settings)
        settings.cloudProvider = CloudProvider.Deepgram
        pipeline.restart(settings)
        assertTrue(made.any { it.engine == TranscriptionEngineKind.Cloud && it.cloudProvider == CloudProvider.Deepgram })
    }
}
