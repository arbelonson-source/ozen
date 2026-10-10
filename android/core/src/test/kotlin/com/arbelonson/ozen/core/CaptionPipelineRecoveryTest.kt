package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private fun fast() = AutoRecoveryPolicy(glitchDelays = listOf(0.01, 0.01), downloadDelays = listOf(0.01))

private fun TestScope.makeRecoveringPipeline(
    audio: FakeAudioCapturer = FakeAudioCapturer(),
    engine: FakeEngine = FakeEngine(),
    policy: AutoRecoveryPolicy,
    clock: TestClock = TestClock(),
): CaptionPipeline = captionPipeline(
    audio = audio,
    engineFactory = { engine },
    embedder = FakeEmbedder(),
    recovery = policy,
    now = { clock.now },
)

private fun oneGlitchRetry() = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList())

class CaptionPipelineRecoveryTest {
    @Test
    fun `the recognizer dropping out mid-conversation recovers by itself`() = runTest {
        val engine = FakeEngine()
        val pipeline = makeRecoveringPipeline(engine = engine, policy = fast())
        pipeline.start(AppSettings.default)
        assertTrue(pipeline.phase.isListening)

        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertEquals(1, pipeline.scheduledRetry?.attempt)
        assertTrue(eventually { pipeline.phase.isListening })
        assertNull(pipeline.scheduledRetry)
    }

    @Test
    fun `the retry is due the delay after now, the time Diagnostics counts down to`() = runTest {
        val engine = FakeEngine()
        val clock = TestClock()
        val pipeline = makeRecoveringPipeline(
            engine = engine,
            policy = AutoRecoveryPolicy(glitchDelays = listOf(30.0), downloadDelays = emptyList()),
            clock = clock,
        )
        pipeline.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.scheduledRetry != null })
        assertEquals(ScheduledRetry(at = clock.now + 30, attempt = 1), pipeline.scheduledRetry)
    }

    @Test
    fun `captions started by hand drop the retry that was lined up, so nothing says they are recovering on their own`() = runTest {
        val engine = FakeEngine()
        val pipeline = makeRecoveringPipeline(
            engine = engine,
            policy = AutoRecoveryPolicy(glitchDelays = listOf(30.0), downloadDelays = emptyList()),
            clock = TestClock(),
        )
        pipeline.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.scheduledRetry != null })
        pipeline.start(AppSettings.default)
        assertTrue(pipeline.phase.isListening)
        assertNull(pipeline.scheduledRetry)
        assertFalse(pipeline.isRecoveringByItself)
    }

    @Test
    fun `a step of getting ready is logged with how long the step before took, not a clock reading`() = runTest {
        val engine = FakeEngine(
            progressUpdates = listOf(
                EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.5),
                EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel),
            ),
        )
        val pipeline = makeRecoveringPipeline(engine = engine, policy = AutoRecoveryPolicy.disabled())
        val took = ArrayList<Double>()
        pipeline.onEvent = { event ->
            (event.kind as? PipelineEvent.Kind.Step)?.afterSeconds?.let { took.add(it) }
        }
        pipeline.start(AppSettings.default)
        assertTrue(took.isNotEmpty())
        assertTrue(took.all { it >= 0 && it < 60 }, "$took")
    }

    @Test
    fun `a mid-stream cloud error that needs a person is reported specifically, not retried as a generic glitch`() = runTest {
        val engine = FakeEngine()
        val pipeline = makeRecoveringPipeline(engine = engine, policy = fast())
        pipeline.start(AppSettings.default)
        assertTrue(pipeline.phase.isListening)

        engine.endStream(throwing = CloudSpeechError.KeyRejected)
        assertTrue(eventually { pipeline.phase.failure != null })
        assertEquals(PipelineFailure.Kind.EngineUnavailable, pipeline.phase.failure?.kind)
        assertEquals(EngineUnavailability.Kind.CloudKeyNeeded, pipeline.phase.failure?.engineUnavailability?.kind)
        assertNull(pipeline.scheduledRetry)
    }

    @Test
    fun `retries stop when the schedule runs out, leaving the failure for a person`() = runTest {
        val audio = FakeAudioCapturer()
        audio.startError = TestError()
        val pipeline = makeRecoveringPipeline(audio = audio, policy = fast())
        pipeline.start(AppSettings.default)
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)

        // Initial attempt plus the two scheduled retries.
        assertTrue(eventually { audio.calls.count { it == "startCapture" } == 3 && pipeline.scheduledRetry == null })
        delay(80.milliseconds)
        assertEquals(3, audio.calls.count { it == "startCapture" })
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)
    }

    @Test
    fun `a denied microphone is never retried`() = runTest {
        val audio = FakeAudioCapturer()
        audio.permissionAnswer = AudioPermission.Denied
        val pipeline = makeRecoveringPipeline(audio = audio, policy = fast())
        pipeline.start(AppSettings.default)
        assertEquals(PipelineFailure.Kind.MicrophonePermissionDenied, pipeline.phase.failure?.kind)
        assertNull(pipeline.scheduledRetry)
    }

    @Test
    fun `stopping by hand cancels a retry that was waiting`() = runTest {
        val engine = FakeEngine()
        val pipeline = makeRecoveringPipeline(
            engine = engine,
            policy = AutoRecoveryPolicy(glitchDelays = listOf(0.15), downloadDelays = emptyList()),
        )
        pipeline.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.scheduledRetry != null })
        pipeline.stop()
        assertNull(pipeline.scheduledRetry)
        delay(250.milliseconds)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `during a phone call nothing is retried, when the call ends, recovery starts fresh`() = runTest {
        val audio = FakeAudioCapturer()
        audio.startError = TestError()
        val pipeline = makeRecoveringPipeline(audio = audio, policy = oneGlitchRetry())
        pipeline.systemInterruptionChanged(true)
        pipeline.start(AppSettings.default)
        assertNotNull(pipeline.phase.failure)
        assertNull(pipeline.scheduledRetry)

        audio.startError = null
        pipeline.systemInterruptionChanged(false)
        assertNotNull(pipeline.scheduledRetry)
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `a minute of healthy listening earns a fresh set of attempts`() = runTest {
        val engine = FakeEngine()
        val clock = TestClock()
        val pipeline = makeRecoveringPipeline(
            engine = engine,
            policy = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList(), healthyListeningSeconds = 60.0),
            clock = clock,
        )
        pipeline.start(AppSettings.default)

        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertTrue(eventually { pipeline.phase.isListening && pipeline.scheduledRetry == null })

        clock.advance(61.0)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertNotNull(pipeline.scheduledRetry)
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `captions stopped and started again by hand get a fresh set of attempts`() = runTest {
        val engine = FakeEngine()
        val pipeline = makeRecoveringPipeline(engine = engine, policy = oneGlitchRetry(), clock = TestClock())
        pipeline.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertTrue(eventually { pipeline.phase.isListening && pipeline.scheduledRetry == null })

        pipeline.stop()
        pipeline.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `a restart for new settings gets a fresh set of attempts too`() = runTest {
        val engine = FakeEngine()
        val pipeline = makeRecoveringPipeline(engine = engine, policy = oneGlitchRetry(), clock = TestClock())
        pipeline.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertTrue(eventually { pipeline.phase.isListening && pipeline.scheduledRetry == null })

        pipeline.restart(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `a phone call holds off the retry that was lined up, captions don't start during the call`() = runTest {
        val engine = FakeEngine()
        val pipeline = makeRecoveringPipeline(
            engine = engine,
            policy = AutoRecoveryPolicy(glitchDelays = listOf(0.3), downloadDelays = emptyList()),
            clock = TestClock(),
        )
        pipeline.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.scheduledRetry != null })
        val prepares = engine.prepareCount

        pipeline.systemInterruptionChanged(true)
        assertNull(pipeline.scheduledRetry)
        delay(0.8.seconds)
        assertNotNull(pipeline.phase.failure)
        assertEquals(prepares, engine.prepareCount)
    }

    @Test
    fun `the end of a phone call gives captions stopped before it a fresh set of attempts`() = runTest {
        val engine = FakeEngine()
        val pipeline = makeRecoveringPipeline(engine = engine, policy = oneGlitchRetry(), clock = TestClock())
        pipeline.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertTrue(eventually { pipeline.phase.isListening && pipeline.scheduledRetry == null })
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertNull(pipeline.scheduledRetry)

        pipeline.systemInterruptionChanged(true)
        pipeline.systemInterruptionChanged(false)
        assertNotNull(pipeline.scheduledRetry)
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `failing again right after a recovery does not get a fresh set of attempts`() = runTest {
        val engine = FakeEngine()
        val pipeline = makeRecoveringPipeline(engine = engine, policy = oneGlitchRetry())
        pipeline.start(AppSettings.default)

        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertTrue(eventually { pipeline.phase.isListening && pipeline.scheduledRetry == null })

        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertNull(pipeline.scheduledRetry)
    }
}
