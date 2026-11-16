package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

private fun half(second: Float) = FloatArray(8_000) { second }

private suspend fun FakeAudioCapturer.feed(seconds: Int, level: Float) {
    repeat(seconds * 2) {
        push(half(level))
        delay(500.milliseconds)
    }
}

class CaptionPipelineSilentMicrophoneTest {
    @Test
    fun `a microphone sending only exact zeros warns after the long wait, keeps listening, and clears when sound returns`() = runTest {
        val (pipeline, audio, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.listenForSoundsOnly(AppSettings.default)

        audio.feed(20, 0f)
        assertFalse(pipeline.soundAlertsNotWorking, "warned too early")
        audio.feed(20, 0f)
        assertTrue(pipeline.soundAlertsNotWorking)
        assertTrue(pipeline.soundAlertsMicrophoneSilent)
        assertTrue(pipeline.isListeningForSoundsOnly)
        assertTrue(pipeline.soundsOnlyChosen)

        audio.feed(60 * 5, 0f)
        assertTrue(pipeline.soundAlertsNotWorking)
        assertTrue(pipeline.soundsOnlyChosen)
        assertFalse(pipeline.soundsOnlyStoppedByItself)
        assertEquals(1, audio.calls.count { it == "startCapture" })

        audio.feed(4, 0.01f)
        assertFalse(pipeline.soundAlertsNotWorking)
        assertFalse(pipeline.soundAlertsMicrophoneSilent)
    }

    @Test
    fun `a quiet room with a faint noise floor never warns`() = runTest {
        val (pipeline, audio, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.listenForSoundsOnly(AppSettings.default)
        audio.feed(10 * 60, 1e-5f)
        assertFalse(pipeline.soundAlertsNotWorking)
        assertFalse(pipeline.soundAlertsMicrophoneSilent)
    }

    @Test
    fun `one stray real chunk in the zeros starts the wait over`() = runTest {
        val (pipeline, audio, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.listenForSoundsOnly(AppSettings.default)
        audio.feed(25, 0f)
        audio.feed(1, 1e-5f)
        audio.feed(25, 0f)
        assertFalse(pipeline.soundAlertsNotWorking)
        audio.feed(10, 0f)
        assertTrue(pipeline.soundAlertsNotWorking)
    }

    @Test
    fun `sound alerts turned off take the silent microphone warning away`() = runTest {
        val (pipeline, audio, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.listenForSoundsOnly(AppSettings.default)
        audio.feed(40, 0f)
        assertTrue(pipeline.soundAlertsNotWorking)
        pipeline.setSoundAlertPreferences(SoundAlertPreferences(isEnabled = false))
        assertFalse(pipeline.soundAlertsNotWorking)
    }

    @Test
    fun `the silent microphone notice says the microphone sends only silence`() {
        Localization.withLanguage(UILanguage.English) {
            val content = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.SoundAlertsMicrophoneSilent)
            assertEquals("Sound alerts aren’t working", content.title)
            assertTrue(content.body.contains("only silence"))
        }
        val cause = StoppedCaptionsNotice.cause(
            phase = PipelinePhase.Paused, retryScheduled = false, systemInterrupted = false,
            callEndedDuringInterruption = false, soundsOnlyChosen = true,
            soundAlertsNotWorking = true, soundAlertsMicrophoneSilent = true,
        )
        assertEquals(StoppedCaptionsNotice.Cause.SoundAlertsMicrophoneSilent, cause)
    }
}
