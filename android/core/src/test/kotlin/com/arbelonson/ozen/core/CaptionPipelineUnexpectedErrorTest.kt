package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

private class Boom(text: String) : IllegalStateException(text)

private class Uncaught {
    val seen = ArrayList<Throwable>()
    private val before = Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler { _, error -> seen.add(error) }
    }

    fun restore() = Thread.setDefaultUncaughtExceptionHandler(before)
}

private fun TestScope.guardedPipeline(
    engine: FakeEngine = FakeEngine(),
    detector: FakeSoundDetector? = null,
    audio: FakeAudioCapturer = FakeAudioCapturer(),
): Pair<CaptionPipeline, CoroutineScope> {
    lateinit var made: CaptionPipeline
    val guarded = errorGuardedScope(StandardTestDispatcher(testScheduler)) { made.unexpectedError(it) }
    made = makePipeline(
        audio = audio,
        engines = mapOf(TranscriptionEngineKind.WhisperKit to engine),
        soundDetector = detector,
        recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList()),
        scope = guarded,
    ).pipeline
    return made to guarded
}

class CaptionPipelineUnexpectedErrorTest {
    @Test
    fun `an error thrown in a guarded coroutine does not reach the uncaught handler`() = runTest {
        val uncaught = Uncaught()
        try {
            val (_, guarded) = guardedPipeline()
            guarded.launch { throw Boom("nobody caught this") }
            advanceUntilIdle()
            assertTrue(uncaught.seen.isEmpty())
        } finally {
            uncaught.restore()
        }
    }

    @Test
    fun `it is written to the event log with its class and message`() = runTest {
        val (pipeline, guarded) = guardedPipeline()
        pipeline.start(AppSettings.default)
        guarded.launch { throw Boom("bad state") }
        assertTrue(eventually {
            pipeline.eventLog.events.map { it.kind }.filterIsInstance<PipelineEvent.Kind.Note>()
                .any { it.text.contains("unexpected error") && it.text.contains("Boom") && it.text.contains("bad state") }
        })
    }

    @Test
    fun `captions go to the normal failed state with a retry that brings them back`() = runTest {
        val (pipeline, guarded) = guardedPipeline()
        pipeline.start(AppSettings.default)
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
        var told: PipelinePhase? = null
        pipeline.onPhaseChange = { if (it is PipelinePhase.Failed) told = it }

        guarded.launch { throw Boom("bad state") }

        assertTrue(eventually { told != null })
        assertEquals(PipelineFailure.Kind.TranscriptionStopped, (told as PipelinePhase.Failed).reason.kind)
        assertTrue((told as PipelinePhase.Failed).reason.isRetryableInApp)
        assertTrue(pipeline.eventLog.events.any { it.kind is PipelineEvent.Kind.RetryScheduled })
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
    }

    @Test
    fun `sound alerts listening alone are retried by the sound retries`() = runTest {
        val detector = FakeSoundDetector()
        val audio = FakeAudioCapturer().apply { requiresPreparedSession = true }
        val (pipeline, guarded) = guardedPipeline(detector = detector, audio = audio)
        pipeline.listenForSoundsOnly(AppSettings.default)
        assertTrue(eventually { pipeline.isListeningForSoundsOnly })
        val opened = audio.calls.count { it == "prepareSession" }

        guarded.launch { throw Boom("bad state") }

        assertTrue(eventually { audio.calls.count { it == "stopCapture" } >= 1 })
        assertTrue(pipeline.soundsOnlyChosen)
        assertTrue(eventually { pipeline.isListeningForSoundsOnly && audio.calls.count { it == "prepareSession" } > opened })
    }

    @Test
    fun `cancellation is not an error`() = runTest {
        val (pipeline, _) = guardedPipeline()
        pipeline.start(AppSettings.default)
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
        pipeline.unexpectedError(CancellationException("done"))
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertFalse(pipeline.eventLog.events.any { it.kind is PipelineEvent.Kind.Note && (it.kind as PipelineEvent.Kind.Note).text.contains("unexpected") })
    }

    @Test
    fun `a handler that throws does not reach the uncaught handler either`() = runTest {
        val uncaught = Uncaught()
        try {
            val guarded = errorGuardedScope(StandardTestDispatcher(testScheduler)) { throw Boom("in the handler") }
            guarded.launch { throw Boom("first") }
            advanceUntilIdle()
            assertTrue(uncaught.seen.isEmpty())
        } finally {
            uncaught.restore()
        }
    }

    @Test
    fun `a handler that throws an Error does not reach the uncaught handler either`() = runTest {
        val uncaught = Uncaught()
        try {
            val guarded = errorGuardedScope(StandardTestDispatcher(testScheduler)) { throw AssertionError("in the handler") }
            guarded.launch { throw Boom("first") }
            advanceUntilIdle()
            assertTrue(uncaught.seen.isEmpty())
        } finally {
            uncaught.restore()
        }
    }
}
