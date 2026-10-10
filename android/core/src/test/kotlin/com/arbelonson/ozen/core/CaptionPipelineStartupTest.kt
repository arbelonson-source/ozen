package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class CaptionPipelineStartupTest {
    @Test
    fun `happy path lands in listening and asks for permission, session, then capture, in that order`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)

        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(TranscriptionEngineKind.WhisperKit, pipeline.activeEngineKind)
        assertEquals(listOf("requestPermission", "prepareSession", "startCapture"), audio.calls)
        assertEquals(1_000.0, pipeline.stats.sessionStartedAt)
    }

    @Test
    fun `microphones are listed BEFORE the engine is prepared, not after`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine))
        var inputsSeenDuringPrepare: List<AudioInputDescriptor> = emptyList()
        var phaseSeenDuringPrepare: PipelinePhase = PipelinePhase.Idle
        engine.duringPrepare = {
            inputsSeenDuringPrepare = pipeline.availableInputs
            phaseSeenDuringPrepare = pipeline.phase
        }

        pipeline.start(AppSettings.default)

        assertEquals(listOf("builtin"), inputsSeenDuringPrepare.map { it.uid })
        assertNotNull(phaseSeenDuringPrepare.preparationProgress)
        assertEquals("builtin", pipeline.selectedInputUID)
    }

    @Test
    fun `engine progress updates are reflected in the phase while preparing`() = runTest {
        val updates = listOf(
            EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.25, detail = "small"),
            EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel),
        )
        val engine = FakeEngine(progressUpdates = updates)
        val (pipeline, _, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine))
        val seen = ArrayList<EnginePreparationProgress>()
        engine.duringPrepare = {
            pipeline.phase.preparationProgress?.let { seen.add(it) }
        }

        pipeline.start(AppSettings.default)

        // The last update emitted is the one visible when prepare finishes.
        assertEquals(updates.last(), seen.lastOrNull())
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a download's progress replaces what's on screen only at another whole percent`() = runTest {
        val updates = listOf(0.100, 0.101, 0.104, 0.106, 0.107).map {
            EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = it, detail = "small")
        }
        val engine = FakeEngine(progressUpdates = updates)
        val (pipeline, _, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine))
        var shown: Double? = null
        engine.duringPrepare = { shown = pipeline.phase.preparationProgress?.fraction }

        pipeline.start(AppSettings.default)

        assertEquals(0.106, shown)
    }

    @Test
    fun `denied microphone permission fails without ever touching the engine or session`() = runTest {
        val audio = FakeAudioCapturer()
        audio.permissionAnswer = AudioPermission.Denied
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(audio = audio, engines = mapOf(TranscriptionEngineKind.WhisperKit to engine))

        pipeline.start(AppSettings.default)

        assertEquals(PipelineFailure.Kind.MicrophonePermissionDenied, pipeline.phase.failure?.kind)
        assertEquals(false, pipeline.phase.failure?.isRetryableInApp)
        assertEquals(true, pipeline.phase.failure?.needsSystemSettings)
        assertEquals(0, engine.prepareCount)
        assertFalse(audio.calls.contains("prepareSession"))
    }

    @Test
    fun `stopped while the audio session is still being set up - nothing starts once it's ready`() = runTest {
        val audio = FakeAudioCapturer()
        audio.holdPrepare = true
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(audio = audio, engines = mapOf(TranscriptionEngineKind.WhisperKit to engine))

        val starting = launch { pipeline.start(AppSettings.default) }
        assertTrue(eventually { audio.isHoldingPrepare })
        pipeline.stop()
        audio.releasePrepare()
        starting.join()

        assertFalse(audio.calls.contains("startCapture"))
        assertEquals(0, engine.prepareCount)
        assertNull(pipeline.phase.failure)
    }

    @Test
    fun `an engine that reports itself unavailable produces a structured failure with a suggestion`() = runTest {
        val why = EngineUnavailability(EngineUnavailability.Kind.LanguageNotSupportedOnDevice, "he-IL on-device model missing")
        val engine = FakeEngine(TranscriptionEngineKind.AppleSpeech, EngineAvailability.Unavailable(why))
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.AppleSpeech
        val (pipeline, audio, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.AppleSpeech to engine))

        pipeline.start(settings)

        assertEquals(PipelineFailure.Kind.EngineUnavailable, pipeline.phase.failure?.kind)
        assertEquals(why, pipeline.phase.failure?.engineUnavailability)
        assertEquals(true, pipeline.phase.failure?.suggestsOtherEngine)
        assertEquals(true, pipeline.phase.failure?.isRetryableInApp)
        assertFalse(audio.calls.contains("startCapture"))
        // Inputs stay listed even though the engine failed - the mic
        // picker must keep working so the user can fix things.
        assertEquals(1, pipeline.availableInputs.size)
    }

    @Test
    fun `a denied speech permission is not retryable in-app`() = runTest {
        val why = EngineUnavailability(EngineUnavailability.Kind.PermissionDenied, "SFSpeechRecognizer denied")
        val engine = FakeEngine(TranscriptionEngineKind.AppleSpeech, EngineAvailability.Unavailable(why))
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.AppleSpeech
        val (pipeline, _, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.AppleSpeech to engine))

        pipeline.start(settings)

        assertEquals(false, pipeline.phase.failure?.isRetryableInApp)
        assertEquals(false, pipeline.phase.failure?.suggestsOtherEngine)
    }

    @Test
    fun `a broken audio session fails with audioSessionFailed`() = runTest {
        val audio = FakeAudioCapturer()
        audio.prepareError = TestError()
        val (pipeline, _, _) = makePipeline(audio = audio)

        pipeline.start(AppSettings.default)

        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)
        assertEquals(true, pipeline.phase.failure?.isRetryableInApp)
    }

    @Test
    fun `no inputs at all is its own failure, not a silent empty picker`() = runTest {
        val audio = FakeAudioCapturer()
        audio.availableInputs = emptyList()
        val (pipeline, _, _) = makePipeline(audio = audio)

        pipeline.start(AppSettings.default)

        assertEquals(PipelineFailure.Kind.NoAudioInputs, pipeline.phase.failure?.kind)
    }

    @Test
    fun `start while already listening is a no-op`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        pipeline.start(AppSettings.default)

        assertEquals(1, audio.calls.count { it == "startCapture" })
    }
}
