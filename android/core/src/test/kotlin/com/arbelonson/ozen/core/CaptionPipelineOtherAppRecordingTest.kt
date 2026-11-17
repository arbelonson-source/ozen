package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

private fun FakeAudioCapturer.openings(): Int = calls.count { it == "startCapture" }

private fun CaptionPipeline.calls(began: Boolean): Int =
    eventLog.events.count { (it.kind as? PipelineEvent.Kind.PhoneCall)?.began == began }

private suspend fun CaptionPipeline.otherAppSilencesAndLifts() {
    systemInterruptionChanged(true)
    delay(1.seconds)
    systemInterruptionChanged(false)
}

private suspend fun TestScope.keepSilencingFor(pipeline: CaptionPipeline, audio: FakeAudioCapturer, seconds: Int) {
    var seen = audio.openings()
    val end = currentTime + seconds * 1_000L
    while (currentTime < end) {
        delay(100.milliseconds)
        if (audio.openings() != seen) {
            seen = audio.openings()
            pipeline.otherAppSilencesAndLifts()
        }
    }
}

private fun TestScope.listeningForSounds(): Pair<CaptionPipeline, FakeAudioCapturer> {
    val audio = FakeAudioCapturer()
    val made = makePipeline(audio = audio, soundDetector = FakeSoundDetector(), now = { 1_000.0 + currentTime / 1_000.0 })
    return made.pipeline to audio
}

class CaptionPipelineOtherAppRecordingTest {
    @Test
    fun `another app that keeps recording is not retried every second`() = runTest {
        val (pipeline, audio) = listeningForSounds()
        pipeline.listenForSoundsOnly(AppSettings.default)
        pipeline.otherAppSilencesAndLifts()

        keepSilencingFor(pipeline, audio, 120)

        assertTrue(audio.openings() <= 10, "opened ${audio.openings()} times in two minutes")
    }

    @Test
    fun `a resume that is silenced again does not reset the wait`() = runTest {
        val (pipeline, audio) = listeningForSounds()
        pipeline.listenForSoundsOnly(AppSettings.default)
        pipeline.otherAppSilencesAndLifts()
        assertTrue(eventually { audio.openings() == 2 })
        pipeline.otherAppSilencesAndLifts()

        delay(2.seconds)

        assertEquals(2, audio.openings())
        assertTrue(eventually(within = 10.seconds) { audio.openings() == 3 })
    }

    @Test
    fun `a resume that holds long enough starts the next wait from the beginning`() = runTest {
        val (pipeline, audio) = listeningForSounds()
        pipeline.listenForSoundsOnly(AppSettings.default)
        pipeline.otherAppSilencesAndLifts()
        assertTrue(eventually { audio.openings() == 2 })
        pipeline.otherAppSilencesAndLifts()
        assertTrue(eventually(within = 10.seconds) { audio.openings() == 3 })

        val hearing = launch {
            while (true) {
                audio.push(FloatArray(1_024) { 0.1f })
                delay(500.milliseconds)
            }
        }
        delay(20.seconds)
        hearing.cancel()
        pipeline.otherAppSilencesAndLifts()

        delay(500.milliseconds)
        assertEquals(4, audio.openings())
    }

    @Test
    fun `the journal says once that another app has the microphone and once that it gave it back`() = runTest {
        val (pipeline, audio) = listeningForSounds()
        pipeline.listenForSoundsOnly(AppSettings.default)
        pipeline.otherAppSilencesAndLifts()

        keepSilencingFor(pipeline, audio, 120)

        assertEquals(1, pipeline.calls(began = true))
        assertEquals(0, pipeline.calls(began = false))

        delay(30.seconds)

        assertEquals(1, pipeline.calls(began = true))
        assertEquals(1, pipeline.calls(began = false))
    }
}
