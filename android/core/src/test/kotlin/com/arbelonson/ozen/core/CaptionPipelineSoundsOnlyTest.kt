package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

private fun chunk() = FloatArray(1_024) { 0.1f }

private fun FakeAudioCapturer.captures(): Int = calls.count { it == "startCapture" }

private suspend fun CaptionPipeline.assertSoundsOnlyAlerts(audio: FakeAudioCapturer, detector: FakeSoundDetector) {
    assertTrue(isListeningForSoundsOnly)
    assertTrue(soundsOnlyChosen)
    assertTrue(stats.soundDetectionRunning)
    audio.push(chunk())
    assertTrue(eventually { detector.chunksSeen >= 1 })
    detector.push(SoundObservation("smoke_detector", 0.9, 100.0))
    assertTrue(eventually { soundAlerts.any { it.event.identifier == "smoke_detector" } })
}

class CaptionPipelineSoundsOnlyTest {
    @Test
    fun `from listening the model is let go and the microphone and classifier go on`() = runTest {
        val engine = FakeEngine()
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine), soundDetector = detector)
        pipeline.start(AppSettings.default)
        assertEquals(1, pipeline.loadedModelCount)

        pipeline.listenForSoundsOnly()

        assertEquals(0, pipeline.loadedModelCount)
        assertFalse(engine.holdsModel)
        assertEquals(1, engine.releaseCount)
        assertEquals(PipelinePhase.Paused, pipeline.phase)
        pipeline.assertSoundsOnlyAlerts(audio, detector)
    }

    @Test
    fun `no engine object is left alive once only sounds are listened for`() = runTest {
        val built = BuiltEngines()
        val detector = FakeSoundDetector()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings -> FakeEngine(kind = settings.engine).also(built::add) },
            soundDetector = detector,
            recovery = AutoRecoveryPolicy.disabled(),
        )
        pipeline.start(AppSettings.default)
        assertEquals(1, built.aliveCount)

        pipeline.listenForSoundsOnly()

        assertTrue(eventually(within = 3.seconds) { built.aliveCount == 0 })
    }

    @Test
    fun `from paused it listens for sounds alone and raises alerts`() = runTest {
        val detector = FakeSoundDetector()
        val audio = FakeAudioCapturer().apply { requiresPreparedSession = true }
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = detector)
        pipeline.start(AppSettings.default)
        pipeline.pause()
        assertEquals(PipelinePhase.Paused, pipeline.phase)

        pipeline.listenForSoundsOnly()

        assertEquals(0, pipeline.loadedModelCount)
        assertEquals(PipelinePhase.Paused, pipeline.phase)
        pipeline.assertSoundsOnlyAlerts(audio, detector)
    }

    @Test
    fun `from idle, with no session ever opened, it opens one and listens for sounds alone`() = runTest {
        val detector = FakeSoundDetector()
        val audio = FakeAudioCapturer().apply { requiresPreparedSession = true }
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = detector)

        pipeline.listenForSoundsOnly(AppSettings.default)

        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertTrue("prepareSession" in audio.calls)
        pipeline.assertSoundsOnlyAlerts(audio, detector)
    }

    @Test
    fun `the screen is told when sounds-only starts from idle, where the phase does not change`() = runTest {
        val (pipeline, _, _) = makePipeline(soundDetector = FakeSoundDetector())
        var told = 0
        pipeline.onPhaseChange = { told += 1 }

        pipeline.listenForSoundsOnly(AppSettings.default)

        assertTrue(told >= 1)
    }

    @Test
    fun `during a call nothing starts`() = runTest {
        val (pipeline, audio, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.systemInterruptionChanged(true)

        pipeline.listenForSoundsOnly(AppSettings.default)

        assertFalse(pipeline.soundsOnlyChosen)
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertEquals(0, audio.captures())
    }

    @Test
    fun `a call pauses sounds-only and its end brings it back by itself`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        pipeline.listenForSoundsOnly()
        assertTrue(pipeline.isListeningForSoundsOnly)

        pipeline.systemInterruptionChanged(true)
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertTrue(pipeline.soundsOnlyChosen)
        assertEquals(PipelinePhase.Paused, pipeline.phase)

        pipeline.systemInterruptionChanged(false)
        assertTrue(eventually { pipeline.isListeningForSoundsOnly })
        assertEquals(0, pipeline.loadedModelCount)
        pipeline.assertSoundsOnlyAlerts(audio, detector)
    }

    @Test
    fun `captions started afterwards stop the sounds first and clear the choice`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        pipeline.listenForSoundsOnly()
        assertTrue(pipeline.isListeningForSoundsOnly)

        pipeline.resume()

        assertFalse(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.soundsOnlyChosen)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(1, pipeline.loadedModelCount)
        assertTrue(audio.calls.lastIndexOf("stopCapture") < audio.calls.lastIndexOf("startCapture"))
    }

    @Test
    fun `stopping by hand ends the sounds too`() = runTest {
        val (pipeline, _, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.listenForSoundsOnly(AppSettings.default)
        assertTrue(pipeline.isListeningForSoundsOnly)

        pipeline.stop()

        assertFalse(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.soundsOnlyChosen)
        assertFalse(pipeline.stats.soundDetectionRunning)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `the notification's stop ends sounds-only`() = runTest {
        val (pipeline, _, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.start(AppSettings.default)
        pipeline.listenForSoundsOnly()

        pipeline.stopFromNotification()

        assertFalse(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.soundsOnlyChosen)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `a memory warning in sounds-only frees nothing more and does not stop the sounds`() = runTest {
        val (pipeline, _, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.start(AppSettings.default)
        pipeline.listenForSoundsOnly()

        pipeline.handleMemoryWarning(200_000_000)

        assertTrue(pipeline.isListeningForSoundsOnly)
        assertTrue(pipeline.soundsOnlyChosen)
        assertTrue(pipeline.stats.soundDetectionRunning)
    }

    @Test
    fun `a microphone that will not open leaves nothing chosen`() = runTest {
        val audio = FakeAudioCapturer().apply { permissionAnswer = AudioPermission.Denied; requiresPreparedSession = true }
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = FakeSoundDetector())

        pipeline.listenForSoundsOnly(AppSettings.default)

        assertFalse(pipeline.soundsOnlyChosen)
        assertFalse(pipeline.isListeningForSoundsOnly)
    }

    @Test
    fun `a microphone that will not open on the tap leaves nothing chosen`() = runTest {
        val audio = FakeAudioCapturer().apply { prepareError = FakeAudioError("FakeAudio", 4); requiresPreparedSession = true }
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = FakeSoundDetector())

        pipeline.listenForSoundsOnly(AppSettings.default)

        assertFalse(pipeline.soundsOnlyChosen)
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.soundsOnlyStoppedByItself)
    }

    @Test
    fun `a capture lost to a route change is opened again, and the alerts still come`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        pipeline.listenForSoundsOnly()
        val before = audio.captures()

        audio.onCaptureLost?.invoke()

        assertTrue(eventually(within = 30.seconds) { audio.captures() > before && pipeline.isListeningForSoundsOnly })
        assertTrue(pipeline.soundsOnlyChosen)
        pipeline.assertSoundsOnlyAlerts(audio, detector)
    }

    @Test
    fun `a microphone that delivers nothing is opened again rather than left claiming to listen`() = runTest {
        val (pipeline, audio, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.listenForSoundsOnly(AppSettings.default)
        assertEquals(1, audio.captures())

        assertTrue(eventually(within = 30.seconds) { audio.captures() >= 2 })
        assertTrue(pipeline.soundsOnlyChosen)
    }

    @Test
    fun `a microphone that dies in the night and never comes back ends the chosen state and says so`() = runTest {
        val audio = FakeAudioCapturer().apply {
            captureFlow = { flow<FloatArray> { throw FakeAudioError("FakeAudio", 3) } as Flow<FloatArray> }
        }
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = FakeSoundDetector())
        var told = 0
        pipeline.onPhaseChange = { told += 1 }

        pipeline.listenForSoundsOnly(AppSettings.default)
        assertTrue(pipeline.soundsOnlyChosen)

        assertTrue(eventually(within = 20.minutes) { !pipeline.soundsOnlyChosen })
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertTrue(pipeline.soundsOnlyStoppedByItself)
        assertEquals(1 + CaptionPipeline.SOUNDS_ONLY_RETRY_SECONDS.size, audio.captures())
        assertTrue(told >= 2)
    }

    @Test
    fun `a capture that just ends is tried again a bounded number of times`() = runTest {
        val audio = FakeAudioCapturer().apply { captureFlow = { emptyFlow() } }
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = FakeSoundDetector())

        pipeline.listenForSoundsOnly(AppSettings.default)

        assertTrue(eventually(within = 20.minutes) { !pipeline.soundsOnlyChosen })
        assertTrue(pipeline.soundsOnlyStoppedByItself)
        assertEquals(1 + CaptionPipeline.SOUNDS_ONLY_RETRY_SECONDS.size, audio.captures())
    }

    @Test
    fun `after a call one failed attempt is tried again until the microphone comes back`() = runTest {
        val detector = FakeSoundDetector()
        val audio = FakeAudioCapturer().apply { requiresPreparedSession = true }
        val (pipeline, _, _) = makePipeline(audio = audio, soundDetector = detector)
        pipeline.listenForSoundsOnly(AppSettings.default)
        pipeline.systemInterruptionChanged(true)
        audio.startError = FakeAudioError("FakeAudio", 5)
        audio.prepareError = FakeAudioError("FakeAudio", 6)

        pipeline.systemInterruptionChanged(false)
        assertTrue(eventually { audio.calls.count { it == "prepareSession" } >= 2 })
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertTrue(pipeline.soundsOnlyChosen)
        audio.startError = null
        audio.prepareError = null

        assertTrue(eventually(within = 30.seconds) { pipeline.isListeningForSoundsOnly })
        assertTrue(pipeline.soundsOnlyChosen)
        pipeline.assertSoundsOnlyAlerts(audio, detector)
    }

    @Test
    fun `while captions load the button stops the start and listens for sounds alone, and the loaded model is let go`() = runTest {
        val gate = PrepareGate()
        val engine = FakeEngine().apply { prepareGate = gate }
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine), soundDetector = detector)
        backgroundScope.launch { pipeline.start(AppSettings.default) }
        assertTrue(eventually { pipeline.phase is PipelinePhase.PreparingEngine && engine.heldPrepares == 1 })

        pipeline.listenForSoundsOnly(AppSettings.default)

        assertEquals(PipelinePhase.Idle, pipeline.phase)
        pipeline.assertSoundsOnlyAlerts(audio, detector)
        gate.open()
        assertTrue(eventually { engine.releaseCount == 1 })
        assertEquals(0, pipeline.loadedModelCount)
        assertTrue(pipeline.isListeningForSoundsOnly)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `after a failure the button listens for sounds alone`() = runTest {
        val engine = FakeEngine().apply { availability = EngineAvailability.unavailable(EngineUnavailability.Kind.ModelLoadFailed, "test") }
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(TranscriptionEngineKind.WhisperKit to engine), soundDetector = detector)
        pipeline.start(AppSettings.default)
        assertTrue(pipeline.phase is PipelinePhase.Failed)

        pipeline.listenForSoundsOnly(AppSettings.default)

        assertEquals(PipelinePhase.Idle, pipeline.phase)
        pipeline.assertSoundsOnlyAlerts(audio, detector)
    }

    @Test
    fun `sounds alone from idle are not off, so the walkthrough shown again stops them`() = runTest {
        val (pipeline, _, _) = makePipeline(soundDetector = FakeSoundDetector())
        assertTrue(pipeline.isOff)

        pipeline.listenForSoundsOnly(AppSettings.default)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertFalse(pipeline.isOff)

        pipeline.stop()
        assertTrue(pipeline.isOff)
    }

    @Test
    fun `with sound alerts switched off nothing starts, and switching them off later ends it`() = runTest {
        val off = AppSettings.default.apply { soundAlerts = SoundAlertPreferences(isEnabled = false) }
        val (silent, silentAudio, _) = makePipeline(soundDetector = FakeSoundDetector())
        silent.listenForSoundsOnly(off)
        assertFalse(silent.soundsOnlyChosen)
        assertEquals(0, silentAudio.captures())

        val (pipeline, audio, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.listenForSoundsOnly(AppSettings.default)
        assertTrue(pipeline.soundsOnlyChosen)
        var told = 0
        pipeline.onPhaseChange = { told += 1 }
        pipeline.setSoundAlertPreferences(SoundAlertPreferences(isEnabled = false))
        assertFalse(pipeline.soundsOnlyChosen)
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.stats.soundDetectionRunning)
        assertEquals("stopCapture", audio.calls.last())
        assertTrue(told >= 1)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `a voice sample taken in sounds-only gives the microphone back to the sounds afterwards`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        pipeline.listenForSoundsOnly()

        val recording = async { pipeline.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { pipeline.isRecordingVoice })
        assertFalse(pipeline.isListeningForSoundsOnly)
        audio.push(FloatArray(8_000) { 0.1f })
        recording.await()

        assertTrue(pipeline.isListeningForSoundsOnly)
        assertTrue(pipeline.soundsOnlyChosen)
        assertEquals(PipelinePhase.Paused, pipeline.phase)
    }
}
