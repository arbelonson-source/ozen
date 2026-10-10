package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineStateTest {
    @Test
    fun `only the startup steps count as transitioning`() {
        assertTrue(PipelinePhase.RequestingMicrophonePermission.isTransitioning)
        assertTrue(PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel)).isTransitioning)
        assertTrue(PipelinePhase.StartingAudio.isTransitioning)
        assertFalse(PipelinePhase.Idle.isTransitioning)
        assertFalse(PipelinePhase.Listening.isTransitioning)
        assertFalse(PipelinePhase.Paused.isTransitioning)
        assertFalse(PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.NoAudioInputs, "")).isTransitioning)
    }

    @Test
    fun `accessors unwrap the associated values`() {
        val progress = EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.5)
        assertEquals(progress, PipelinePhase.PreparingEngine(progress).preparationProgress)
        assertNull(PipelinePhase.Listening.preparationProgress)
        val failure = PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "x")
        assertEquals(failure, PipelinePhase.Failed(failure).failure)
        assertNull(PipelinePhase.Listening.failure)
        assertTrue(PipelinePhase.Listening.isListening)
    }

    @Test
    fun `retryability follows the kind of failure, and permission denials point to system Settings`() {
        assertFalse(PipelineFailure(PipelineFailure.Kind.MicrophonePermissionDenied, "").isRetryableInApp)
        assertTrue(PipelineFailure(PipelineFailure.Kind.MicrophonePermissionDenied, "").needsSystemSettings)
        assertTrue(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "").isRetryableInApp)
        assertTrue(PipelineFailure(PipelineFailure.Kind.TranscriptionStopped, "").isRetryableInApp)
        assertTrue(PipelineFailure(PipelineFailure.Kind.NoAudioInputs, "").isRetryableInApp)

        val speechDenied = PipelineFailure(
            PipelineFailure.Kind.EngineUnavailable, "",
            EngineUnavailability(EngineUnavailability.Kind.PermissionDenied, ""),
        )
        assertFalse(speechDenied.isRetryableInApp)
        val downloadFailed = PipelineFailure(
            PipelineFailure.Kind.EngineUnavailable, "",
            EngineUnavailability(EngineUnavailability.Kind.ModelDownloadFailed, ""),
        )
        assertTrue(downloadFailed.isRetryableInApp)
    }

    @Test
    fun `suggesting the other engine only makes sense for engine-specific gaps`() {
        fun failure(kind: EngineUnavailability.Kind) =
            PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", EngineUnavailability(kind, ""))
        assertTrue(failure(EngineUnavailability.Kind.LanguageNotSupportedOnDevice).suggestsOtherEngine)
        assertTrue(failure(EngineUnavailability.Kind.ModelDownloadFailed).suggestsOtherEngine)
        assertTrue(failure(EngineUnavailability.Kind.ModelLoadFailed).suggestsOtherEngine)
        assertFalse(failure(EngineUnavailability.Kind.PermissionDenied).suggestsOtherEngine)
        assertFalse(failure(EngineUnavailability.Kind.TemporarilyUnavailable).suggestsOtherEngine)
        assertTrue(failure(EngineUnavailability.Kind.NoInternet).suggestsOtherEngine)
        assertFalse(failure(EngineUnavailability.Kind.CloudKeyNeeded).suggestsOtherEngine)
        assertFalse(failure(EngineUnavailability.Kind.CloudOutOfCredit).suggestsOtherEngine)
        assertFalse(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "").suggestsOtherEngine)
    }

    @Test
    fun `caption lag is the gap between newest audio and newest token, never negative`() {
        val stats = PipelineStats()
        assertNull(stats.captionLagSeconds)
        stats.lastAudioAt = 100.0
        stats.lastTokenAt = 99.2
        stats.hasOpenLine = true
        assertNotNull(stats.captionLagSeconds)
        assertTrue(abs((stats.captionLagSeconds ?: 0.0) - 0.8) < 0.0001)
        stats.lastTokenAt = 101.0
        assertEquals(0.0, stats.captionLagSeconds)
    }

    @Test
    fun `EngineAvailability convenience constructor and accessor`() {
        val availability = EngineAvailability.unavailable(EngineUnavailability.Kind.ModelLoadFailed, "boom")
        assertEquals(EngineUnavailability.Kind.ModelLoadFailed, availability.unavailability?.kind)
        assertEquals("boom", availability.unavailability?.detail)
        assertNull(EngineAvailability.Available.unavailability)
    }

    @Test
    fun `a phase's step keeps the stage and model of a download but not how far along it is`() {
        val stage = EnginePreparationProgress.Stage.DownloadingModel
        val at10 = PipelinePhase.PreparingEngine(EnginePreparationProgress(stage, fraction = 0.1, detail = "small"))
        val at11 = PipelinePhase.PreparingEngine(EnginePreparationProgress(stage, fraction = 0.11, detail = "small"))
        assertNotEquals(at10, at11)
        assertEquals(at10.step, at11.step)
        assertNotEquals(
            at10.step,
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "small")).step,
        )
        assertNotEquals(
            at10.step,
            PipelinePhase.PreparingEngine(EnginePreparationProgress(stage, fraction = 0.1, detail = "base")).step,
        )
        assertEquals(PipelinePhase.Listening, PipelinePhase.Listening.step)
        assertEquals(
            PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "x")),
            PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "x")).step,
        )
    }

    @Test
    fun `a download's progress is news at another step, model or whole percent, or a second later`() {
        val stage = EnginePreparationProgress.Stage.DownloadingModel
        val shown = EnginePreparationProgress(stage, fraction = 0.421, detail = "small")
        fun downloading(fraction: Double, variant: String = "small") =
            EnginePreparationProgress(stage, fraction = fraction, detail = variant)
        assertFalse(downloading(0.4249).isNews(shown, shownAt = 10.0, now = 10.5))
        assertTrue(downloading(0.426).isNews(shown, shownAt = 10.0, now = 10.5))
        assertTrue(downloading(0.4249).isNews(shown, shownAt = 10.0, now = 11.0))
        assertTrue(downloading(0.421, "base").isNews(shown, shownAt = 10.0, now = 10.1))
        val loading = EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "small")
        assertTrue(loading.isNews(shown, shownAt = 10.0, now = 10.1))
        assertFalse(loading.isNews(loading, shownAt = 10.0, now = 50.0))
    }

    @Test
    fun `caption lag counts only while a line is still being written`() {
        val stats = PipelineStats()
        assertNull(stats.captionLagSeconds)
        stats.lastTokenAt = 100.0
        stats.lastAudioAt = 103.0
        stats.hasOpenLine = true
        assertEquals(3.0, stats.captionLagSeconds)
        stats.hasOpenLine = false
        stats.lastAudioAt = 160.0
        assertEquals(0.0, stats.captionLagSeconds)
    }
}
