package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

private fun FakeAudioCapturer.captures(): Int = calls.count { it == "startCapture" }

class CaptionPipelineNotificationStopTest {
    @Test
    fun `stop from the notification while the model loads leaves the microphone off when loading ends`() = runTest {
        val engine = FakeEngine()
        val gate = PrepareGate()
        engine.prepareGate = gate
        val (pipeline, audio, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine))
        val starting = launch { pipeline.start(AppSettings.default) }
        assertTrue(eventually { engine.prepareCount == 1 })

        pipeline.stopFromNotification()
        gate.open()
        starting.join()
        delay(100.milliseconds)

        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertEquals(0, audio.captures())
    }

    @Test
    fun `stop from the notification while an automatic retry is waiting cancels the retry`() = runTest {
        val (pipeline, audio, _) = makePipeline(
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.3), downloadDelays = emptyList()),
        )
        pipeline.start(AppSettings.default)
        audio.onCaptureLost?.invoke()
        assertNotNull(pipeline.scheduledRetry)

        pipeline.stopFromNotification()
        delay(600.milliseconds)

        assertNull(pipeline.scheduledRetry)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertEquals(1, audio.captures())
    }

    @Test
    fun `stop from the notification while listening pauses so captions can be resumed`() = runTest {
        val (pipeline, _, _) = makePipeline()
        pipeline.start(AppSettings.default)

        pipeline.stopFromNotification()

        assertEquals(PipelinePhase.Paused, pipeline.phase)
    }
}
