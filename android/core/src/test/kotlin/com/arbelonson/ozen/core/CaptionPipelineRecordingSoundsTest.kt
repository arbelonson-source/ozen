package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

private fun chunk() = FloatArray(1_024) { 0.1f }

private suspend fun CaptionPipeline.handCaptionsToARecording() {
    stopFromNotification()
    releaseModels()
    beginRecordingTranscription()
    listenForSoundsOnly()
}

class CaptionPipelineRecordingSoundsTest {
    @Test
    fun `sound alerts still arrive while a recording is transcribed, with the speech model let go`() = runTest {
        val engine = FakeEngine()
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine), soundDetector = detector)
        pipeline.start(AppSettings.default)

        pipeline.handCaptionsToARecording()

        assertEquals(0, pipeline.loadedModelCount)
        assertEquals(PipelinePhase.Paused, pipeline.phase)
        assertTrue(pipeline.isTranscribingRecording)
        assertTrue(pipeline.soundsOnlyShown)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen >= 1 })
        detector.push(SoundObservation("smoke_detector", 0.9, 100.0))
        assertTrue(eventually { pipeline.soundAlerts.any { it.event.identifier == "smoke_detector" } })
    }

    @Test
    fun `captions come back when the recording is done, and the sounds go on through them`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        pipeline.handCaptionsToARecording()

        pipeline.endRecordingTranscription()
        pipeline.resume()

        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertFalse(pipeline.soundsOnlyChosen)
        assertEquals(1, pipeline.loadedModelCount)
        assertTrue(pipeline.stats.soundDetectionRunning)
        audio.push(chunk())
        detector.push(SoundObservation("smoke_detector", 0.9, 100.0))
        assertTrue(eventually { pipeline.soundAlerts.any { it.event.identifier == "smoke_detector" } })
    }

    @Test
    fun `a long transcription with the microphone delivering raises no stall and no silence`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        pipeline.handCaptionsToARecording()
        val opened = audio.calls.count { it == "startCapture" }

        val feeding = launch {
            repeat(600) {
                audio.push(chunk())
                delay(500.milliseconds)
            }
        }
        delay(300.seconds)
        feeding.cancel()

        assertEquals(0, pipeline.stats.audioStalls)
        assertEquals(opened, audio.calls.count { it == "startCapture" }, "the microphone was opened again while it delivered")
        assertTrue(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.soundAlertsNotWorking)
    }

    @Test
    fun `stopping during the transcription ends the sounds too and nothing comes back`() = runTest {
        val (pipeline, _, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.start(AppSettings.default)
        pipeline.handCaptionsToARecording()

        pipeline.stopFromNotification()

        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.soundsOnlyChosen)
    }

    @Test
    fun `with sound alerts off the recording simply pauses the captions`() = runTest {
        val (pipeline, audio, _) = makePipeline(soundDetector = FakeSoundDetector())
        val settings = AppSettings.default.copy().apply { soundAlerts = soundAlerts.copy(isEnabled = false) }
        pipeline.start(settings)

        pipeline.handCaptionsToARecording()

        assertFalse(pipeline.isListeningForSoundsOnly)
        assertEquals(PipelinePhase.Paused, pipeline.phase)
        assertEquals(1, audio.calls.count { it == "startCapture" })
    }
}
