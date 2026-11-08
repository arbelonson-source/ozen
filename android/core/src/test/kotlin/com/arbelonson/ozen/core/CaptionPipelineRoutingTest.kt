package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val builtIn = AudioInputDescriptor(uid = "builtin", portName = "Phone microphone", portType = AudioPortType.BuiltInMic)
private val lapel = AudioInputDescriptor(uid = "bluetooth:aa", portName = "Lapel Pro", portType = AudioPortType.Bluetooth)
private val wired = AudioInputDescriptor(uid = "wired:", portName = "Headset", portType = AudioPortType.Wired)

private fun bluetoothAudio(): FakeAudioCapturer {
    val audio = FakeAudioCapturer()
    audio.availableInputs = listOf(builtIn, lapel, wired)
    return audio
}

private fun bluetoothSettings(): AppSettings {
    val settings = AppSettings.default
    settings.preferredInputUID = lapel.uid
    return settings
}

class CaptionPipelineRoutingTest {
    @Test
    fun `the Bluetooth advice is given only when the picked microphone was Bluetooth`() {
        val fromWired = RoutedElsewhere(wired, builtIn).detail
        assertTrue("Phone microphone" in fromWired, fromWired)
        assertTrue("Bluetooth" !in fromWired && "USB-C" !in fromWired, fromWired)
        val fromLapel = RoutedElsewhere(lapel, builtIn).detail
        assertTrue("Phone microphone" in fromLapel && "Bluetooth" in fromLapel, fromLapel)
    }

    @Test
    fun `a microphone nobody picked is never said to be not recording`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(AppSettings.default)
        runCurrent()
        val shown = pipeline.selectedInputUID
        audio.inputInUse = listOf(builtIn, lapel).first { it.uid != shown }
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_500)
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `one of the phone's own microphones standing in for another says nothing`() = runTest {
        val bottom = AudioInputDescriptor(uid = "builtInMic:bottom", portName = "Pixel 8", portType = AudioPortType.BuiltInMic)
        val back = AudioInputDescriptor(uid = "builtInMic:back", portName = "Pixel 8", portType = AudioPortType.BuiltInMic)
        val audio = FakeAudioCapturer()
        audio.availableInputs = listOf(bottom, back)
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        val settings = AppSettings.default
        settings.preferredInputUID = bottom.uid
        pipeline.start(settings)
        runCurrent()
        assertEquals(bottom.uid, pipeline.selectedInputUID)
        audio.inputInUse = back
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_500)
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `a picked microphone the phone does not record from is named after two seconds`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(bluetoothSettings())
        runCurrent()
        assertEquals(lapel.uid, pipeline.selectedInputUID)

        audio.inputInUse = builtIn
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(1_900)
        assertNull(pipeline.routedElsewhere)
        advanceTimeBy(200)
        assertEquals(RoutedElsewhere(lapel, builtIn), pipeline.routedElsewhere)
        assertTrue(pipeline.routedElsewhere?.title?.contains("Lapel Pro") == true)
        assertTrue(pipeline.routedElsewhere?.detail?.contains("Phone microphone") == true)
    }

    @Test
    fun `the notice goes when the pick is recording again`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(bluetoothSettings())
        audio.inputInUse = builtIn
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_100)
        assertEquals(RoutedElsewhere(lapel, builtIn), pipeline.routedElsewhere)

        audio.inputInUse = lapel
        audio.simulateRouteChange(audio.availableInputs)
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `picking another microphone takes the notice away`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(bluetoothSettings())
        audio.inputInUse = builtIn
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_100)
        assertEquals(RoutedElsewhere(lapel, builtIn), pipeline.routedElsewhere)

        pipeline.selectInput(builtIn.uid)
        assertNull(pipeline.routedElsewhere)
        advanceTimeBy(2_500)
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `a routing that differs for under two seconds says nothing`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(bluetoothSettings())
        audio.inputInUse = builtIn
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(1_500)
        assertNull(pipeline.routedElsewhere)
        audio.inputInUse = lapel
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_500)
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `captions that are not listening say nothing about the routing`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        audio.inputInUse = builtIn
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_500)
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `a routing the phone cannot name says nothing`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(bluetoothSettings())
        audio.inputInUse = null
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_500)
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `stopping captions takes the notice away`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(bluetoothSettings())
        audio.inputInUse = builtIn
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_100)
        assertEquals(RoutedElsewhere(lapel, builtIn), pipeline.routedElsewhere)
        pipeline.stop()
        assertNull(pipeline.routedElsewhere)
    }

    @Test
    fun `a dismissed notice stays away until the routing changes`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(bluetoothSettings())
        audio.inputInUse = builtIn
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_100)
        pipeline.dismissRoutedElsewhere()
        assertNull(pipeline.routedElsewhere)
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_500)
        assertNull(pipeline.routedElsewhere)

        audio.inputInUse = lapel
        audio.simulateRouteChange(audio.availableInputs)
        audio.inputInUse = builtIn
        audio.simulateRouteChange(audio.availableInputs)
        advanceTimeBy(2_100)
        assertEquals(RoutedElsewhere(lapel, builtIn), pipeline.routedElsewhere)
    }

    @Test
    fun `the pipeline reports the microphone in use`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        assertNull(pipeline.inputInUse)
        audio.inputInUse = wired
        assertEquals(wired, pipeline.inputInUse)
    }

    @Test
    fun `a Bluetooth microphone she picked that drops while listening is a lost microphone`() = runTest {
        val audio = bluetoothAudio()
        val (pipeline, _, _) = makePipeline(audio = audio, audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(bluetoothSettings())
        runCurrent()
        audio.selectedInputUID = builtIn.uid
        audio.simulateRouteChange(listOf(builtIn, wired))
        assertEquals(lapel, pipeline.microphoneDrop.lost)
    }
}
