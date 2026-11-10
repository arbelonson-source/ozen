package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private val phoneMic = AudioInputDescriptor(uid = "builtin", portName = "Phone microphone", portType = AudioPortType.BuiltInMic)
private val lapelMic = AudioInputDescriptor(uid = "bluetooth:aa", portName = "Lapel Pro", portType = AudioPortType.Bluetooth)
private val usbMic = AudioInputDescriptor(uid = "usb:1", portName = "Desk mic", portType = AudioPortType.Usb)

private fun quickRetries() = AutoRecoveryPolicy(glitchDelays = listOf(0.01, 0.01, 0.01, 0.01), downloadDelays = emptyList())

private fun picked(uid: String?): AppSettings {
    val settings = AppSettings.default
    settings.preferredInputUID = uid
    return settings
}

/** Zeros from every microphone but the phone's own, which hears a little; stops once [done]. */
private suspend fun FakeAudioCapturer.feedUntil(maxChunks: Int = 80, done: () -> Boolean) {
    repeat(maxChunks) {
        if (done()) return
        val heard = selectedInputUID == phoneMic.uid
        push(FloatArray(1_600) { if (heard) 0.01f else 0f })
        delay(100.milliseconds)
    }
}

private suspend fun FakeAudioCapturer.feedPhoneSound(chunks: Int) {
    repeat(chunks) {
        push(FloatArray(1_600) { 0.01f })
        delay(100.milliseconds)
    }
}

class CaptionPipelineMicrophoneFallbackTest {
    private fun TestScope.fallbackPipeline(): Pair<CaptionPipeline, FakeAudioCapturer> {
        val audio = FakeAudioCapturer()
        audio.availableInputs = listOf(phoneMic, lapelMic)
        val (pipeline, _, _) = makePipeline(
            audio = audio,
            recovery = quickRetries(),
            audioWatchdog = AudioStallWatchdog(stallSeconds = 6.0, silenceSeconds = 0.5),
        )
        return pipeline to audio
    }

    @Test
    fun `a picked Bluetooth microphone silent twice in a row hands captions to the phone's own, keeping the pick`() = runTest {
        val (pipeline, audio) = fallbackPipeline()
        pipeline.start(picked(lapelMic.uid))

        audio.feedUntil { audio.preparedFor.size >= 3 && pipeline.phase.isListening }
        audio.feedPhoneSound(25)

        assertEquals<List<String?>>(listOf(lapelMic.uid, lapelMic.uid, phoneMic.uid), audio.preparedFor)
        assertTrue(pipeline.phase.isListening, "${pipeline.phase}")
        assertEquals(2, pipeline.stats.audioStalls)
        assertEquals(lapelMic.uid, pipeline.activeSettings?.preferredInputUID)
        assertEquals(RoutedElsewhere(lapelMic, phoneMic), pipeline.routedElsewhere)
        assertNull(pipeline.microphoneDrop.lost)
        assertTrue(pipeline.routedElsewhere?.title?.contains("Lapel Pro") == true)
        assertTrue(pipeline.routedElsewhere?.detail?.contains("Phone microphone") == true)
    }

    @Test
    fun `a silent pick's failure names it`() = runTest {
        val audio = FakeAudioCapturer()
        audio.availableInputs = listOf(phoneMic, usbMic)
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog(stallSeconds = 6.0, silenceSeconds = 0.5))
        pipeline.start(picked(usbMic.uid))

        audio.feedUntil { pipeline.phase.failure != null }

        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)
        assertEquals("Desk mic", pipeline.phase.failure?.inputName)
    }

    @Test
    fun `the phone's own microphone, picked or not, is never swapped or named`() = runTest {
        for (pick in listOf(null, phoneMic.uid)) {
            val audio = FakeAudioCapturer()
            audio.availableInputs = listOf(phoneMic, lapelMic)
            val (pipeline, _, _) = makePipeline(
                audio = audio,
                recovery = quickRetries(),
                audioWatchdog = AudioStallWatchdog(stallSeconds = 6.0, silenceSeconds = 0.5),
            )
            pipeline.start(picked(pick))
            repeat(40) {
                audio.push(FloatArray(1_600))
                delay(100.milliseconds)
            }

            assertTrue(pipeline.stats.audioStalls >= 3, "$pick: ${pipeline.stats.audioStalls}")
            assertEquals(List(audio.preparedFor.size) { pick }, audio.preparedFor, "$pick")
            assertNull(pipeline.eventLog.events.firstNotNullOfOrNull { (it.kind as? PipelineEvent.Kind.Failed)?.failure?.inputName }, "$pick")
            pipeline.stop()
        }
    }

    @Test
    fun `silence while the phone records from another input than the accepted pick is not the pick's, and hands nothing over`() = runTest {
        val (pipeline, audio) = fallbackPipeline()
        audio.inputInUse = phoneMic
        pipeline.start(picked(lapelMic.uid))
        repeat(40) {
            audio.push(FloatArray(1_600))
            delay(100.milliseconds)
        }

        assertTrue(pipeline.stats.audioStalls >= 3, "${pipeline.stats.audioStalls}")
        assertEquals<List<String?>>(List(audio.preparedFor.size) { lapelMic.uid }, audio.preparedFor)
        assertNull(pipeline.eventLog.events.firstNotNullOfOrNull { (it.kind as? PipelineEvent.Kind.Failed)?.failure?.inputName })
        pipeline.stop()
    }

    @Test
    fun `picking a microphone ends the fallback`() = runTest {
        val (pipeline, audio) = fallbackPipeline()
        pipeline.start(picked(lapelMic.uid))
        audio.feedUntil { audio.preparedFor.size >= 3 && pipeline.phase.isListening }
        audio.feedPhoneSound(25)
        assertEquals(RoutedElsewhere(lapelMic, phoneMic), pipeline.routedElsewhere)

        pipeline.selectInput(lapelMic.uid)
        audio.feedPhoneSound(25)

        assertEquals(lapelMic.uid, pipeline.selectedInputUID)
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `the pick coming back ends the fallback and is used again`() = runTest {
        val (pipeline, audio) = fallbackPipeline()
        pipeline.start(picked(lapelMic.uid))
        audio.feedUntil { audio.preparedFor.size >= 3 && pipeline.phase.isListening }
        audio.feedPhoneSound(25)
        assertEquals(RoutedElsewhere(lapelMic, phoneMic), pipeline.routedElsewhere)

        audio.simulateRouteChange(listOf(phoneMic))
        audio.simulateRouteChange(listOf(phoneMic, lapelMic))
        audio.feedPhoneSound(25)

        assertEquals(lapelMic.uid, pipeline.selectedInputUID)
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `stopping captions ends the fallback, and the next start uses the pick`() = runTest {
        val (pipeline, audio) = fallbackPipeline()
        pipeline.start(picked(lapelMic.uid))
        audio.feedUntil { audio.preparedFor.size >= 3 && pipeline.phase.isListening }
        assertEquals(phoneMic.uid, audio.preparedFor.last())

        pipeline.stop()
        pipeline.start(picked(lapelMic.uid))

        assertEquals(lapelMic.uid, audio.preparedFor.last())
        assertNull(pipeline.routedElsewhere)
    }
}
