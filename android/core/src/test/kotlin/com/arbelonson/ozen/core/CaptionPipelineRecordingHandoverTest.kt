package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

private fun chunk() = FloatArray(1_024) { 0.1f }

private suspend fun CaptionPipeline.handOver(handover: CaptionsHandover): Boolean {
    val handed = handover.begin(phase, isRecoveringByItself)
    stopFromNotification()
    releaseModels()
    if (handed) listenForSoundsOnly(AppSettings.default)
    return handed
}

private suspend fun CaptionPipeline.giveBack(handover: CaptionsHandover) {
    endRecordingTranscription()
    if (handover.end(phase)) start(AppSettings.default)
}

class CaptionPipelineRecordingHandoverTest {
    @Test
    fun `a start still loading keeps sound alerts on during the recording and starts again after it`() = runTest {
        val engine = FakeEngine()
        val gate = PrepareGate()
        engine.prepareGate = gate
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine), soundDetector = detector)
        val handover = CaptionsHandover()
        val starting = launch { pipeline.start(AppSettings.default) }
        assertTrue(eventually { engine.prepareCount == 1 })

        assertTrue(pipeline.handOver(handover))
        gate.open()
        starting.join()
        pipeline.waitOutAbandonedLoad()
        pipeline.beginRecordingTranscription()

        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertEquals(0, pipeline.loadedModelCount)
        assertTrue(pipeline.isListeningForSoundsOnly)
        audio.push(chunk())
        detector.push(SoundObservation("smoke_detector", 0.9, 100.0))
        assertTrue(eventually { pipeline.soundAlerts.any { it.event.identifier == "smoke_detector" } })

        pipeline.giveBack(handover)

        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertFalse(pipeline.soundsOnlyChosen)
        assertEquals(1, pipeline.loadedModelCount)
    }

    @Test
    fun `a start waiting for a retry is handed over without the retry firing, and starts once after`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(
            soundDetector = detector,
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.3), downloadDelays = emptyList()),
        )
        val handover = CaptionsHandover()
        pipeline.start(AppSettings.default)
        audio.onCaptureLost?.invoke()
        assertTrue(pipeline.isRecoveringByItself)

        assertTrue(pipeline.handOver(handover))
        pipeline.beginRecordingTranscription()
        delay(600.milliseconds)

        assertNull(pipeline.scheduledRetry)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertTrue(pipeline.isListeningForSoundsOnly)
        val opened = audio.calls.count { it == "startCapture" }

        pipeline.giveBack(handover)
        delay(600.milliseconds)

        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(opened + 1, audio.calls.count { it == "startCapture" })
    }

    @Test
    fun `stopping during the recording ends the sounds and nothing starts after it`() = runTest {
        val (pipeline, audio, _) = makePipeline(
            soundDetector = FakeSoundDetector(),
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.3), downloadDelays = emptyList()),
        )
        val handover = CaptionsHandover()
        pipeline.start(AppSettings.default)
        audio.onCaptureLost?.invoke()
        assertTrue(pipeline.handOver(handover))
        pipeline.beginRecordingTranscription()

        handover.cancel()
        pipeline.stopFromNotification()
        pipeline.giveBack(handover)

        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.soundsOnlyChosen)
    }

    @Test
    fun `with sound alerts off a loading start is only stopped for the recording and started after it`() = runTest {
        val engine = FakeEngine()
        val gate = PrepareGate()
        engine.prepareGate = gate
        val (pipeline, _, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine))
        val settings = AppSettings.default.copy().apply { soundAlerts = soundAlerts.copy(isEnabled = false) }
        val handover = CaptionsHandover()
        val starting = launch { pipeline.start(settings) }
        assertTrue(eventually { engine.prepareCount == 1 })

        assertTrue(handover.begin(pipeline.phase, pipeline.isRecoveringByItself))
        pipeline.stopFromNotification()
        gate.open()
        starting.join()

        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertTrue(handover.end(pipeline.phase))
    }
}
