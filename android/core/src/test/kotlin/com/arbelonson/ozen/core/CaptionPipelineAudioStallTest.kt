package com.arbelonson.ozen.core

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

private fun quickWatchdog() = AudioStallWatchdog(stallSeconds = 0.5)

private fun FakeAudioCapturer.startCaptures(): Int = calls.count { it == "startCapture" }

class CaptionPipelineRefreshTest {
    @Test
    fun `refresh asks the system again even when captions never started, without recording`() = runTest {
        val audio = FakeAudioCapturer()
        audio.availableInputs = emptyList()
        val builtIn = AudioInputDescriptor(uid = "built-in", portName = "iPhone Microphone", portType = AudioPortType.BuiltInMic)
        audio.inputsOnRefresh = listOf(builtIn)
        val (pipeline, _, _) = makePipeline(audio = audio)
        assertTrue(pipeline.availableInputs.isEmpty())

        pipeline.refreshInputs()
        assertEquals(listOf(builtIn), pipeline.availableInputs)
        assertEquals(listOf("refreshInputs"), audio.calls)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }
}

class CaptionPipelineAudioStallTest {
    @Test
    fun `a microphone that stops delivering audio becomes a visible audio failure`() = runTest {
        val (pipeline, audio, _) = makePipeline(audioWatchdog = quickWatchdog())
        pipeline.start(AppSettings.default)
        audio.push(FloatArray(1_600))

        assertTrue(eventually { pipeline.phase.failure?.kind == PipelineFailure.Kind.AudioSessionFailed })
        assertEquals(1, pipeline.stats.audioStalls)
        assertEquals("stopCapture", audio.calls.last())
    }

    @Test
    fun `captions started again after a stop mid-stall get the watchdog's whole wait, not what was left of it`() = runTest {
        val (pipeline, audio, _) = makePipeline(audioWatchdog = AudioStallWatchdog(stallSeconds = 4.0))
        pipeline.start(AppSettings.default)
        audio.push(FloatArray(1_600))
        delay(3.seconds)
        assertTrue(pipeline.phase.isListening)
        pipeline.stop()
        // A Bluetooth microphone can take a second or two to deliver its
        // first audio after a start.
        pipeline.start(AppSettings.default)
        delay(2.5.seconds)
        assertTrue(pipeline.phase.isListening)
    }

    @Test
    fun `a microphone the phone gave up setting up again fails at once, not after the watchdog's wait`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        audio.push(FloatArray(1_600))
        assertTrue(pipeline.phase.isListening)

        audio.onCaptureLost?.invoke()
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)
        assertEquals(1, pipeline.stats.audioStalls)
        assertEquals("stopCapture", audio.calls.last())
    }

    @Test
    fun `a microphone given up during a phone call is left to the call's end, like a stall`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        pipeline.systemInterruptionChanged(true)
        audio.onCaptureLost?.invoke()
        assertTrue(pipeline.phase.isListening)
        assertEquals(0, pipeline.stats.audioStalls)
    }

    @Test
    fun `captions stopped for a microphone problem with no retry left try again when the microphones change`() = runTest {
        val clock = TestClock()
        val (pipeline, audio, _) = makePipeline(now = { clock.now })
        pipeline.start(AppSettings.default)
        audio.onCaptureLost?.invoke()
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)
        assertNull(pipeline.scheduledRetry)

        audio.onInputsChanged?.invoke()
        assertTrue(eventually { pipeline.phase.isListening })
        assertEquals(2, audio.startCaptures())

        audio.onCaptureLost?.invoke()
        clock.advance(10.0)
        audio.onInputsChanged?.invoke()
        delay(200.milliseconds)
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)

        clock.advance(30.0)
        audio.onInputsChanged?.invoke()
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `captions brought back by a change of microphones get a fresh set of automatic retries`() = runTest {
        val clock = TestClock()
        val (pipeline, audio, _) = makePipeline(
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList()),
            now = { clock.now },
        )
        pipeline.start(AppSettings.default)
        audio.onCaptureLost?.invoke()
        assertTrue(eventually { pipeline.phase.isListening && pipeline.scheduledRetry == null })
        audio.onCaptureLost?.invoke()
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)
        assertNull(pipeline.scheduledRetry)

        audio.onInputsChanged?.invoke()
        assertTrue(eventually { pipeline.phase.isListening })
        audio.onCaptureLost?.invoke()
        assertNotNull(pipeline.scheduledRetry)
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `a microphone change during a phone call does not restart captions stopped for a microphone problem`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        audio.onCaptureLost?.invoke()
        pipeline.systemInterruptionChanged(true)
        audio.onInputsChanged?.invoke()
        delay(200.milliseconds)
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)
        assertEquals(1, audio.startCaptures())
    }

    @Test
    fun `captions that never got the microphone since launch still try again when a microphone arrives`() = runTest {
        val audio = FakeAudioCapturer()
        audio.prepareError = FakeAudioError("AVAudioSession", 561_017_449)
        val (pipeline, _, _) = makePipeline(audio = audio)
        pipeline.start(AppSettings.default)
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)

        audio.prepareError = null
        audio.simulateRouteChange(listOf(AudioInputDescriptor(uid = "aid", portName = "Hearing Aid", portType = AudioPortType.Bluetooth)))
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `a microphone that comes back during a voice recording brings captions back once the recording ends`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        audio.onCaptureLost?.invoke()
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)

        val recording = async { pipeline.captureEnrollmentSamples(1.0) }
        assertTrue(eventually { pipeline.isRecordingVoice })
        audio.simulateRouteChange(listOf(AudioInputDescriptor(uid = "builtin", portName = "iPhone Microphone", portType = AudioPortType.BuiltInMic)))
        delay(100.milliseconds)
        assertTrue(pipeline.isRecordingVoice)
        audio.push(FloatArray(16_000) { 0.1f })
        val sample = recording.await()
        assertEquals(16_000, sample.size)
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `a quiet room still delivers audio, so captions keep listening`() = runTest {
        // A wider window than the other tests, so a busy CI machine that
        // delays one 50 ms sleep past a tick doesn't fail it, while the
        // audio still runs twice as long as the window.
        val (pipeline, audio, _) = makePipeline(audioWatchdog = AudioStallWatchdog(stallSeconds = 1.0))
        pipeline.start(AppSettings.default)
        repeat(40) {
            audio.push(FloatArray(800))
            delay(50.milliseconds)
        }
        assertTrue(pipeline.phase.isListening)
        assertEquals(0, pipeline.stats.audioStalls)
    }

    @Test
    fun `no failure while a phone call holds the microphone, and the watch resumes after it`() = runTest {
        val (pipeline, _, _) = makePipeline(audioWatchdog = quickWatchdog())
        pipeline.start(AppSettings.default)
        pipeline.systemInterruptionChanged(true)
        delay(1_200.milliseconds)
        assertTrue(pipeline.phase.isListening)

        pipeline.systemInterruptionChanged(false)
        assertTrue(eventually { pipeline.phase.failure?.kind == PipelineFailure.Kind.AudioSessionFailed })
    }

    @Test
    fun `automatic recovery starts capture again after a stall`() = runTest {
        val (pipeline, audio, _) = makePipeline(
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList()),
            audioWatchdog = quickWatchdog(),
        )
        pipeline.start(AppSettings.default)

        assertTrue(eventually { audio.startCaptures() == 2 })
        assertTrue(eventually { pipeline.phase.isListening })
        assertEquals(1, pipeline.stats.audioStalls)

        // The report tells the story in order.
        val story = pipeline.eventLog.events.take(5).map { event ->
            when (val kind = event.kind) {
                PipelineEvent.Kind.Listening -> "listening"
                PipelineEvent.Kind.MicrophoneStalled -> "stalled"
                is PipelineEvent.Kind.Failed -> "failed ${kind.failure.kind.rawValue}"
                is PipelineEvent.Kind.RetryScheduled -> "retry ${kind.attempt}"
                is PipelineEvent.Kind.PhoneCall -> "call"
                is PipelineEvent.Kind.MemoryWarning -> "memory"
                is PipelineEvent.Kind.Step, is PipelineEvent.Kind.Input, is PipelineEvent.Kind.Note -> "journal only"
            }
        }
        assertEquals(listOf("listening", "stalled", "failed audioSessionFailed", "retry 1", "listening"), story)
    }

    @Test
    fun `the journal is told each step of getting ready and how long the one before took, the microphone, and every logged event`() = runTest {
        val (pipeline, _, _) = makePipeline(audioWatchdog = AudioStallWatchdog.disabled)
        val lines = ArrayList<String>()
        pipeline.onEvent = { lines.add(it.description) }
        pipeline.start(AppSettings.default)

        assertTrue(lines.any { it.startsWith("engine: checkingSupport") })
        assertTrue(lines.any { it.startsWith("starting audio (previous step took ") })
        assertTrue(lines.any { it.startsWith("microphone: ") })
        assertEquals("listening", lines.last())
        assertEquals(listOf("listening"), pipeline.eventLog.events.map { it.description })
    }

    @Test
    fun `a phone call is logged when it starts and ends, once each`() = runTest {
        val (pipeline, _, _) = makePipeline(audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.start(AppSettings.default)
        pipeline.systemInterruptionChanged(true)
        pipeline.systemInterruptionChanged(true)
        pipeline.systemInterruptionChanged(false)
        val calls = pipeline.eventLog.events.mapNotNull { event ->
            (event.kind as? PipelineEvent.Kind.PhoneCall)?.began
        }
        assertEquals(listOf(true, false), calls)
    }
}

/** Holds each embedding until the test lets it go (or a second passes). */
class BlockingEmbedder : SpeakerEmbedding {
    private val lock = Any()
    private val release = Semaphore(0)
    private var started = false
    private var finished = false

    val state: Pair<Boolean, Boolean> get() = synchronized(lock) { started to finished }

    fun letGo() {
        release.release()
    }

    override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? {
        synchronized(lock) { started = true }
        release.tryAcquire(1, TimeUnit.SECONDS)
        synchronized(lock) { finished = true }
        return floatArrayOf(1f, 0f, 0f)
    }
}

class CaptionPipelineEmbeddingThreadTest {
    @Test
    fun `the main actor stays free while a voice is being analysed, and the speaker is still assigned`() = runTest {
        val embedder = BlockingEmbedder()
        val audio = FakeAudioCapturer()
        val pipeline = captionPipeline(
            audio = audio,
            engineFactory = { FakeEngine() },
            embedder = embedder,
            recovery = AutoRecoveryPolicy.disabled(),
            embedderContext = Dispatchers.Default,
        )
        pipeline.start(AppSettings.default)
        audio.push(FloatArray(24_000) { 0.5f })

        // This loop runs in the pipeline's own context. If the analysis ran
        // there too, the loop could only look again after it finished.
        eventuallyInRealTime { embedder.state.first }
        val seenMidAnalysis = embedder.state.first && !embedder.state.second
        embedder.letGo()

        assertTrue(seenMidAnalysis)
        assertTrue(eventuallyInRealTime { pipeline.speakerClusters.size == 1 })
    }
}
