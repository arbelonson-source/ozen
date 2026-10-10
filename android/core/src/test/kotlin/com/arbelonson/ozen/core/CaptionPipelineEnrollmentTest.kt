package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

private fun token(id: UUID, text: String, final: Boolean = false, at: Double = 1_000.0) =
    TranscriptToken(utteranceID = id, text = text, isFinal = final, timestamp = at)

private val whisper = TranscriptionEngineKind.WhisperKit

private fun FakeAudioCapturer.startCaptures(): Int = calls.count { it == "startCapture" }

class CaptionPipelineEnrollmentTest {
    @Test
    fun `a voice sample recorded while captions wait to retry gives the microphone back to sound alerts`() = runTest {
        val engine = FakeEngine(availability = EngineAvailability.unavailable(EngineUnavailability.Kind.TemporarilyUnavailable, "busy"))
        val (pipeline, audio, _) = makePipeline(
            engines = mapOf(whisper to engine),
            soundDetector = FakeSoundDetector(),
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(1000.0), downloadDelays = emptyList()),
        )
        pipeline.start(AppSettings.default)
        assertTrue(eventually { pipeline.scheduledRetry != null && pipeline.stats.soundDetectionRunning })

        val recording = async { pipeline.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { pipeline.isRecordingVoice })
        assertFalse(pipeline.stats.soundDetectionRunning)
        audio.push(FloatArray(8_000) { 0.1f })
        assertEquals(8_000, recording.await().size)
        assertNotNull(pipeline.scheduledRetry)
        assertTrue(pipeline.stats.soundDetectionRunning)
    }

    @Test
    fun `enrollment records through the live capture path, pausing and resuming captions around it`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)

        val progress = ArrayList<Double>()
        val recording = async { pipeline.captureEnrollmentSamples(1.0) { progress.add(it) } }
        assertTrue(eventually { audio.startCaptures() == 2 })
        assertEquals(PipelinePhase.Paused, pipeline.phase)
        audio.push(FloatArray(8_000) { 0.1f })
        audio.push(FloatArray(8_000) { 0.1f })
        val samples = recording.await()

        assertEquals(16_000, samples.size)
        assertEquals(listOf(0.5, 1.0), progress)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(3, audio.startCaptures())
    }

    @Test
    fun `captions that start again after a voice recording keep their microphone once the recording's time is up`() = runTest {
        val (pipeline, audio, _) = makePipeline(audioWatchdog = AudioStallWatchdog.disabled)
        pipeline.enrollmentStallSeconds = 0.2
        pipeline.start(AppSettings.default)
        val recording = async { pipeline.captureEnrollmentSamples(0.1) }
        assertTrue(eventually { audio.startCaptures() == 2 })
        audio.push(FloatArray(1_600) { 0.1f })
        recording.await()
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        val resumed = audio.calls.size

        delay(1.seconds)
        assertFalse(audio.calls.drop(resumed).contains("stopCapture"))
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `stopping a voice recording ends it at once, not after its full length`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        var samples: FloatArray? = null
        val recording = launch { samples = pipeline.captureEnrollmentSamples(30.0) }
        assertTrue(eventually { audio.calls.contains("startCapture") })
        audio.push(FloatArray(1_600) { 0.1f })
        recording.cancel()
        val started = testScheduler.timeSource.markNow()
        recording.join()

        assertTrue(started.elapsedNow() < 2.seconds)
        assertTrue(samples!!.size <= 1_600)
        assertEquals("stopCapture", audio.calls.last())
    }

    @Test
    fun `a start or resume asked for during a voice recording leaves the recording its microphone`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        val recording = async { pipeline.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { audio.calls.contains("startCapture") })
        val startsBefore = audio.startCaptures()

        pipeline.start(AppSettings.default)
        pipeline.resume(AppSettings.default)

        assertEquals(startsBefore, audio.startCaptures())
        audio.push(FloatArray(8_000) { 0.1f })
        assertEquals(8_000, recording.await().size)
        assertEquals(false, pipeline.isRecordingVoice)
    }

    @Test
    fun `a voice recorded while captions are still starting brings them back afterwards instead of leaving them paused`() = runTest {
        val slow = FakeEngine()
        val gate = PrepareGate()
        slow.prepareGate = gate
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to slow))
        val starting = launch { pipeline.start(AppSettings.default) }
        while (slow.prepareCount == 0) yield()
        assertTrue(pipeline.phase.isTransitioning)

        val recording = async { pipeline.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { audio.calls.contains("startCapture") })
        audio.push(FloatArray(8_000) { 0.1f })
        gate.open()
        assertEquals(8_000, recording.await().size)
        starting.join()
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
    }

    @Test
    fun `a restart asked for while a voice sample records waits for it, then runs with the new settings`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        val recording = async { pipeline.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { pipeline.isRecordingVoice })

        val changed = AppSettings.default
        changed.whisperModelVariant = "changed-while-recording"
        pipeline.restart(changed)
        audio.push(FloatArray(8_000) { 0.1f })

        assertEquals(8_000, recording.await().size)
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening }, "phase=${pipeline.phase}")
        assertEquals("changed-while-recording", pipeline.activeSettings?.whisperModelVariant)
    }

    @Test
    fun `captions stopped by hand while a restart waits for a voice sample stay stopped`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        val recording = async { pipeline.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { pipeline.isRecordingVoice })
        pipeline.restart(AppSettings.default)
        pipeline.stop()
        audio.push(FloatArray(8_000) { 0.1f })
        recording.await()

        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `enrollment while idle leaves the pipeline idle afterwards`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        val recording = async { pipeline.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { audio.calls.contains("startCapture") })
        audio.push(FloatArray(8_000) { 0.1f })
        val samples = recording.await()

        assertEquals(8_000, samples.size)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `a glitched buffer during enrollment is recorded as silence, so the voice print can still be made`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        val recording = async { pipeline.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { audio.calls.contains("startCapture") })
        val glitched = FloatArray(8_000) { 0.1f }
        glitched[100] = Float.NaN
        glitched[200] = Float.POSITIVE_INFINITY
        audio.push(glitched)
        val samples = recording.await()

        assertEquals(8_000, samples.size)
        assertTrue(samples.all { it.isFinite() })
        assertTrue(samples[100] == 0f && samples[101] == 0.1f)
    }

    @Test
    fun `enrollment before captions ever ran sets up the audio session itself`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        audio.requiresPreparedSession = true
        val recording = async { pipeline.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { audio.startCaptures() == 2 })
        audio.push(FloatArray(8_000) { 0.1f })
        val samples = recording.await()

        assertEquals(8_000, samples.size)
        assertEquals(listOf("startCapture", "requestPermission", "prepareSession", "startCapture"), audio.calls.take(4))
    }

    @Test
    fun `a microphone that delivers nothing ends the recording instead of hanging it`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.enrollmentStallSeconds = 0.1
        val started = testScheduler.timeSource.markNow()
        val recording = async { pipeline.captureEnrollmentSamples(0.1) }
        assertTrue(eventually { audio.calls.contains("startCapture") })
        audio.push(FloatArray(400) { 0.1f })
        val samples = recording.await()

        assertEquals(400, samples.size)
        assertTrue(started.elapsedNow() < 2.seconds)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `stopping an enrollment midway ends the recording at once and brings captions back`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        var heard = 0.0
        var samples: FloatArray? = null
        val recording = launch { samples = pipeline.captureEnrollmentSamples(30.0) { heard = it } }
        assertTrue(eventually { audio.startCaptures() == 2 })
        audio.push(FloatArray(8_000) { 0.1f })
        assertTrue(eventually { heard > 0 })

        val stopped = testScheduler.timeSource.markNow()
        recording.cancel()
        recording.join()

        assertTrue(stopped.elapsedNow() < 2.seconds)
        assertEquals(8_000, samples!!.size)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a voice sample that begins while the phone's model is about to cover for the cloud brings the cover once it ends`() = runTest {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "offline"))
        val phone = FakeEngine(kind = whisper)
        val audio = FakeAudioCapturer()
        val pipeline = captionPipeline(
            audio = audio,
            engineFactory = { settings -> if (settings.engine == TranscriptionEngineKind.Cloud) cloud else phone },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        var recording: Deferred<FloatArray>? = null
        val scope = this
        phone.duringPendingDownloadCheck = {
            if (recording == null) recording = scope.async { pipeline.captureEnrollmentSamples(1.0) }
        }
        val onCloud = AppSettings.default
        onCloud.engine = TranscriptionEngineKind.Cloud
        pipeline.start(onCloud)
        assertTrue(eventually { pipeline.isRecordingVoice })
        audio.push(FloatArray(16_000) { 0.1f })
        recording?.await()

        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
        assertTrue(pipeline.isCoveringForCloud)
    }

    @Test
    fun `without microphone permission enrollment records nothing and sets nothing up`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        audio.requiresPreparedSession = true
        audio.permissionAnswer = AudioPermission.Denied
        val samples = pipeline.captureEnrollmentSamples(0.5)

        assertTrue(samples.isEmpty())
        assertFalse(audio.calls.contains("prepareSession"))
    }

    @Test
    fun `an empty token for an unknown utterance creates no row`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        engine.emit(token(UUID.randomUUID(), ""))
        engine.emit(token(UUID.randomUUID(), "ממשי"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertEquals("ממשי", pipeline.segments.firstOrNull()?.text)
        assertEquals(2, pipeline.stats.tokensReceived)
    }
}
