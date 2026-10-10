package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenAwakePolicyTest {
    @Test
    fun `preparing the model always keeps the screen on, whatever the setting or the quiet`() {
        val phases = listOf(
            PipelinePhase.RequestingMicrophonePermission,
            PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.1)),
            PipelinePhase.PreparingEngine(EnginePreparationProgress(stage = EnginePreparationProgress.Stage.LoadingModel)),
            PipelinePhase.StartingAudio,
        )
        for (phase in phases) {
            assertTrue(ScreenAwakePolicy.shouldKeepAwake(phase, keepAwakeWhileListening = false))
            assertTrue(ScreenAwakePolicy.shouldKeepAwake(phase, keepAwakeWhileListening = true, lastActivityAt = 0.0, now = 10_000.0))
        }
    }

    @Test
    fun `listening follows the setting - idle, paused and failed let the phone lock`() {
        assertTrue(ScreenAwakePolicy.shouldKeepAwake(PipelinePhase.Listening, keepAwakeWhileListening = true))
        assertFalse(ScreenAwakePolicy.shouldKeepAwake(PipelinePhase.Listening, keepAwakeWhileListening = false))
        val phases = listOf(
            PipelinePhase.Idle,
            PipelinePhase.Paused,
            PipelinePhase.Failed(PipelineFailure(kind = PipelineFailure.Kind.AudioSessionFailed, detail = "")),
        )
        for (phase in phases) {
            assertFalse(ScreenAwakePolicy.shouldKeepAwake(phase, keepAwakeWhileListening = true))
        }
    }

    @Test
    fun `while listening the screen stays on during talk, and may lock after a long quiet`() {
        val quiet = ScreenAwakePolicy.quietLockSeconds
        val lastWords = 1_000.0
        val listening = PipelinePhase.Listening
        assertTrue(ScreenAwakePolicy.shouldKeepAwake(listening, true, lastActivityAt = lastWords, now = lastWords + 60))
        assertTrue(ScreenAwakePolicy.shouldKeepAwake(listening, true, lastActivityAt = lastWords, now = lastWords + quiet - 1))
        assertFalse(ScreenAwakePolicy.shouldKeepAwake(listening, true, lastActivityAt = lastWords, now = lastWords + quiet))
        assertFalse(ScreenAwakePolicy.shouldKeepAwake(listening, true, lastActivityAt = lastWords, now = lastWords + 8 * 3_600))
        assertTrue(quiet >= 10 * 60)
    }
}
