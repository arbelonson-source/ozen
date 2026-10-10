package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

private fun token(id: UUID, text: String, final: Boolean = false, at: Double = 1_000.0) =
    TranscriptToken(utteranceID = id, text = text, isFinal = final, timestamp = at)

private val whisper = TranscriptionEngineKind.WhisperKit

private class BeamLog {
    val values = ArrayList<Int>()

    fun add(beam: Int) {
        values.add(beam)
    }
}

class CaptionPipelineLifecycleTest {
    @Test
    fun `the engine stream ending on its own while listening is surfaced as a retryable failure`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)

        engine.endStream(throwing = TestError())

        assertTrue(eventually { pipeline.phase.failure != null })
        assertEquals(PipelineFailure.Kind.TranscriptionStopped, pipeline.phase.failure?.kind)
        assertEquals(true, pipeline.phase.failure?.isRetryableInApp)
        assertEquals("stopCapture", audio.calls.lastOrNull())
    }

    @Test
    fun `a sound choice, the speaker sensitivity or the microphone changed while listening stays after an automatic retry`() = runTest {
        val engine = FakeEngine()
        val audio = FakeAudioCapturer()
        audio.availableInputs = audio.availableInputs + AudioInputDescriptor("usb", "USB Microphone", AudioPortType.Usb)
        val (pipeline, _, _) = makePipeline(
            audio = audio,
            engines = mapOf(whisper to engine),
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList()),
        )
        val settings = AppSettings.default
        settings.preferredInputUID = "builtin"
        pipeline.start(settings)
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })

        val sounds = settings.soundAlerts.copy(mutedIdentifiers = settings.soundAlerts.mutedIdentifiers + "door_bell")
        pipeline.setSoundAlertPreferences(sounds)
        pipeline.setSpeakerSimilarityThreshold(0.9f)
        assertTrue(pipeline.selectInput("usb"))
        val prepared = audio.calls.count { it == "prepareSession" }

        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening && audio.calls.count { it == "prepareSession" } > prepared })
        assertEquals(sounds, pipeline.soundPolicy.preferences)
        assertEquals(0.9f, pipeline.speakerSimilarityThreshold)
        assertEquals("usb", audio.selectedInputUID)
    }

    @Test
    fun `after tapping Retry once the automatic attempts ran out, the next glitch is retried by itself again`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(
            engines = mapOf(whisper to engine),
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList()),
        )
        pipeline.start(AppSettings.default)
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening && engine.prepareCount >= 2 })
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        delay(100.milliseconds)
        assertNull(pipeline.scheduledRetry)

        pipeline.retryAfterTap(AppSettings.default)
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
        engine.endStream(throwing = TestError())
        assertTrue(eventually { pipeline.phase.failure != null })
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
    }

    @Test
    fun `stop returns to idle and stops capture`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        pipeline.stop()

        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertEquals("stopCapture", audio.calls.lastOrNull())
    }

    @Test
    fun `switching engines restarts with the new engine and keeps the transcript`() = runTest {
        val whisperEngine = FakeEngine(kind = TranscriptionEngineKind.WhisperKit)
        val apple = FakeEngine(kind = TranscriptionEngineKind.AppleSpeech)
        val (pipeline, audio, log) = makePipeline(
            engines = mapOf(whisper to whisperEngine, TranscriptionEngineKind.AppleSpeech to apple),
        )
        pipeline.start(AppSettings.default)
        whisperEngine.emit(token(UUID.randomUUID(), "לפני"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.AppleSpeech
        pipeline.restart(settings)

        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeEngineKind)
        assertEquals(1, pipeline.segments.size)
        assertEquals(1, pipeline.stats.engineRestarts)
        assertEquals(1, audio.calls.count { it == "stopCapture" })
        assertEquals(2, audio.calls.count { it == "startCapture" })
        assertEquals(2, log.calls)

        apple.emit(token(UUID.randomUUID(), "אחרי"))
        assertTrue(eventually { pipeline.segments.size == 2 })
    }

    @Test
    fun `restarting with the same engine settings reuses the prepared engine instead of building a new one`() = runTest {
        val whisperEngine = FakeEngine(kind = TranscriptionEngineKind.WhisperKit)
        val (pipeline, _, log) = makePipeline(engines = mapOf(whisper to whisperEngine))
        pipeline.start(AppSettings.default)
        pipeline.restart(AppSettings.default)

        assertEquals(1, log.calls)
        assertEquals(2, whisperEngine.prepareCount)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a different Whisper model variant is a different engine`() = runTest {
        val (pipeline, _, log) = makePipeline()
        pipeline.start(AppSettings.default)
        val settings = AppSettings.default
        settings.whisperModelVariant = "large-v3_turbo"
        pipeline.restart(settings)

        assertEquals(2, log.calls)
    }

    @Test
    fun `switching to another model lets go of the one before, so two models are never kept loaded`() = runTest {
        val built = BuiltEngines()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                val engine = FakeEngine(kind = settings.engine)
                built.add(engine)
                engine
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        pipeline.start(AppSettings.default)
        val turbo = AppSettings.default
        turbo.whisperModelVariant = "large-v3_turbo"
        pipeline.restart(turbo)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertTrue(eventually { built.aliveCount == 1 })

        // Going back builds the first one again rather than having kept it.
        pipeline.restart(AppSettings.default)
        assertEquals(3, built.count)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a new home-computer beam builds a new engine, so the next connection asks for it`() = runTest {
        val built = BuiltEngines()
        val beams = BeamLog()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                beams.add(settings.homeServerBeam)
                val engine = FakeEngine(kind = settings.engine)
                built.add(engine)
                engine
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.HomeServer
        settings.homeServerBeam = 5
        pipeline.start(settings)
        settings.homeServerBeam = 2
        pipeline.restart(settings)
        assertEquals(listOf(5, 2), beams.values)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a memory warning with captions stopped lets go of the loaded engine - starting again builds it anew`() = runTest {
        val built = BuiltEngines()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                val engine = FakeEngine(kind = settings.engine)
                built.add(engine)
                engine
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        pipeline.start(AppSettings.default)
        pipeline.stop()
        pipeline.handleMemoryWarning(footprintBytes = 812_000_000)
        assertTrue(eventually(within = 10.seconds) { built.aliveCount == 0 })
        assertEquals(PipelineEvent.Kind.MemoryWarning(812), pipeline.eventLog.events.lastOrNull()?.kind)

        pipeline.start(AppSettings.default)
        assertEquals(2, built.count)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `the memory warning counts a megabyte as a million bytes, like the report's memory line beside it`() = runTest {
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { FakeEngine(kind = it.engine) },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        pipeline.handleMemoryWarning(footprintBytes = 851_443_712)
        assertEquals(PipelineEvent.Kind.MemoryWarning(851), pipeline.eventLog.events.lastOrNull()?.kind)
    }

    @Test
    fun `a memory warning while captions run keeps the engine, so pausing right after is instant`() = runTest {
        val built = BuiltEngines()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                val engine = FakeEngine(kind = settings.engine)
                built.add(engine)
                engine
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        pipeline.start(AppSettings.default)
        pipeline.handleMemoryWarning()
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(1, built.count)
        assertEquals(1, built.aliveCount)
    }

    @Test
    fun `a memory warning while paused lets go of the engine too - resuming builds it again rather than risk the system ending the app`() = runTest {
        val built = BuiltEngines()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                val engine = FakeEngine(kind = settings.engine)
                built.add(engine)
                engine
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        pipeline.start(AppSettings.default)
        pipeline.pause()
        pipeline.handleMemoryWarning()
        assertTrue(eventually(within = 10.seconds) { built.aliveCount == 0 })

        pipeline.resume()
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(2, built.count)
        assertEquals(1, built.aliveCount)
    }

    @Test
    fun `a memory warning after a failure lets the engine go, unless a retry is on its way`() = runTest {
        for (retryComing in listOf(false, true)) {
            val built = BuiltEngines()
            val audio = FakeAudioCapturer()
            audio.startError = TestError()
            val pipeline = captionPipeline(
                audio = audio,
                engineFactory = { settings ->
                    val engine = FakeEngine(kind = settings.engine)
                    built.add(engine)
                    engine
                },
                embedder = FakeEmbedder(),
                recovery = if (retryComing) {
                    AutoRecoveryPolicy(glitchDelays = listOf(600.0), downloadDelays = emptyList())
                } else {
                    AutoRecoveryPolicy.disabled()
                },
            )
            pipeline.start(AppSettings.default)
            assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)
            assertEquals(retryComing, pipeline.scheduledRetry != null)
            assertTrue(eventually { built.aliveCount == 1 })

            pipeline.handleMemoryWarning()
            if (retryComing) {
                delay(200.milliseconds)
                assertEquals(1, built.aliveCount)
                pipeline.stop()
            } else {
                assertTrue(eventually(within = 10.seconds) { built.aliveCount == 0 })
            }
        }
    }

    @Test
    fun `retry after a failure starts again with the same settings`() = runTest {
        val audio = FakeAudioCapturer()
        audio.startError = TestError()
        val (pipeline, _, _) = makePipeline(audio = audio)
        pipeline.start(AppSettings.default)
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)

        audio.startError = null
        val phases = ArrayList<PipelinePhase>()
        pipeline.onPhaseChange = { phases.add(it) }
        pipeline.retry()

        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertFalse(phases.contains(PipelinePhase.Idle))
    }

    @Test
    fun `pause stops capture and resume starts again`() = runTest {
        val (pipeline, audio, _) = makePipeline()
        pipeline.start(AppSettings.default)
        pipeline.pause()
        assertEquals(PipelinePhase.Paused, pipeline.phase)
        assertEquals("stopCapture", audio.calls.lastOrNull())

        pipeline.resume()
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `resume picks up settings changed while paused, not the ones from before the pause`() = runTest {
        val (pipeline, _, _) = makePipeline(
            engines = mapOf(
                whisper to FakeEngine(kind = TranscriptionEngineKind.WhisperKit),
                TranscriptionEngineKind.AppleSpeech to FakeEngine(kind = TranscriptionEngineKind.AppleSpeech),
            ),
        )
        pipeline.start(AppSettings.default)
        pipeline.pause()

        val changed = AppSettings.default
        changed.engine = TranscriptionEngineKind.AppleSpeech
        pipeline.resume(changed)

        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeSettings?.engine)
    }

    @Test
    fun `retry picks up settings changed while failed, not the ones from before the failure`() = runTest {
        val audio = FakeAudioCapturer()
        audio.startError = TestError()
        val (pipeline, _, _) = makePipeline(
            audio = audio,
            engines = mapOf(
                whisper to FakeEngine(kind = TranscriptionEngineKind.WhisperKit),
                TranscriptionEngineKind.AppleSpeech to FakeEngine(kind = TranscriptionEngineKind.AppleSpeech),
            ),
        )
        pipeline.start(AppSettings.default)
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, pipeline.phase.failure?.kind)

        audio.startError = null
        val changed = AppSettings.default
        changed.engine = TranscriptionEngineKind.AppleSpeech
        pipeline.retry(changed)

        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeSettings?.engine)
    }

    @Test
    fun `progress from a superseded run cannot clobber the new run's phase`() = runTest {
        val slow = FakeEngine(
            progressUpdates = listOf(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.1)),
        )
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to slow))
        val first = launch { pipeline.start(AppSettings.default) }
        // Let the first run get as far as requesting permission, then
        // restart underneath it.
        yield()
        pipeline.stop()
        pipeline.start(AppSettings.default)
        first.join()

        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a restart while an abandoned preparation still runs waits for it, then starts - never two models loading at once`() = runTest {
        val slow = FakeEngine()
        val gate = PrepareGate()
        slow.prepareGate = gate
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to slow))
        val first = launch { pipeline.start(AppSettings.default) }
        while (slow.prepareCount == 0) yield()
        pipeline.stop()
        val second = launch { pipeline.start(AppSettings.default) }
        repeat(50) { yield() }
        assertEquals(1, slow.prepareCount)

        gate.open()
        first.join()
        second.join()
        assertEquals(2, slow.prepareCount)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `switching to the home computer while the phone's model still loads starts at once, and a later phone start still waits for that load`() = runTest {
        val slow = FakeEngine()
        val gate = PrepareGate()
        slow.prepareGate = gate
        val home = FakeEngine(kind = TranscriptionEngineKind.HomeServer)
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to slow, TranscriptionEngineKind.HomeServer to home))
        val first = launch { pipeline.start(AppSettings.default) }
        while (slow.prepareCount == 0) yield()
        val homeSettings = AppSettings.default
        homeSettings.engine = TranscriptionEngineKind.HomeServer
        pipeline.restart(homeSettings)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(1, home.prepareCount)

        pipeline.stop()
        val phoneAgain = launch { pipeline.start(AppSettings.default) }
        repeat(50) { yield() }
        assertEquals(1, slow.prepareCount)
        gate.open()
        first.join()
        phoneAgain.join()
        assertEquals(2, slow.prepareCount)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `two model switches while an abandoned load finishes - the last choice starts, not the one in between`() = runTest {
        val slow = FakeEngine()
        val gate = PrepareGate()
        slow.prepareGate = gate
        val middle = FakeEngine()
        val last = FakeEngine()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                when (settings.whisperModelVariant) {
                    "middle" -> middle
                    "last" -> last
                    else -> slow
                }
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        val first = launch { pipeline.start(AppSettings.default) }
        while (slow.prepareCount == 0) yield()
        val middleSettings = AppSettings.default
        middleSettings.whisperModelVariant = "middle"
        val lastSettings = AppSettings.default
        lastSettings.whisperModelVariant = "last"
        val second = launch { pipeline.restart(middleSettings) }
        repeat(50) { yield() }
        val third = launch { pipeline.restart(lastSettings) }
        repeat(50) { yield() }
        gate.open()
        first.join()
        second.join()
        third.join()
        assertEquals(1, last.prepareCount)
        assertEquals(0, middle.prepareCount)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a start waiting on an abandoned download shows that download's progress, not loading throughout, and then starts`() = runTest {
        val download = EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.4, detail = "large-v3")
        val slow = FakeEngine(progressUpdates = listOf(download))
        val gate = PrepareGate()
        slow.prepareGate = gate
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to slow))
        var seenWhileWaiting: PipelinePhase? = null
        slow.duringPrepare = { if (seenWhileWaiting == null) seenWhileWaiting = pipeline.phase }
        val first = launch { pipeline.start(AppSettings.default) }
        while (slow.prepareCount == 0) yield()
        pipeline.stop()
        val second = launch { pipeline.start(AppSettings.default) }
        repeat(50) { yield() }
        assertEquals(
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel)),
            pipeline.phase,
        )

        gate.open()
        first.join()
        second.join()
        assertEquals(PipelinePhase.PreparingEngine(download), seenWhileWaiting)
        assertEquals(2, slow.prepareCount)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `choosing another model while one downloads stops that download instead of waiting it out`() = runTest {
        val downloading = EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.2, detail = "big")
        val big = FakeEngine(progressUpdates = listOf(downloading))
        val rest = PrepareGate()
        big.afterProgressGate = rest
        val small = FakeEngine()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { if (it.whisperModelVariant == "big") big else small },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        val bigSettings = AppSettings.default
        bigSettings.whisperModelVariant = "big"
        val smallSettings = AppSettings.default
        smallSettings.whisperModelVariant = "small"

        val first = launch { pipeline.start(bigSettings) }
        while (pipeline.phase != PipelinePhase.PreparingEngine(downloading)) yield()
        pipeline.restart(smallSettings)
        first.join()
        assertEquals(1, big.downloadCancels)
        assertEquals(1, small.prepareCount)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a restart on the same model while it downloads waits for that download and leaves it going`() = runTest {
        val downloading = EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.2, detail = "big")
        val big = FakeEngine(progressUpdates = listOf(downloading))
        val rest = PrepareGate()
        big.afterProgressGate = rest
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to big))

        val first = launch { pipeline.start(AppSettings.default) }
        while (pipeline.phase != PipelinePhase.PreparingEngine(downloading)) yield()
        val second = launch { pipeline.restart(AppSettings.default) }
        repeat(50) { yield() }
        assertEquals(0, big.downloadCancels)
        rest.open()
        first.join()
        second.join()
        assertEquals(0, big.downloadCancels)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `two model switches while an abandoned download shows its progress - the last choice still starts`() = runTest {
        val slow = FakeEngine(
            progressUpdates = listOf(
                EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.4, detail = "large-v3"),
                EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "large-v3", isFirstTime = true),
            ),
        )
        val gate = PrepareGate()
        slow.prepareGate = gate
        val middle = FakeEngine()
        val last = FakeEngine()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                when (settings.whisperModelVariant) {
                    "middle" -> middle
                    "last" -> last
                    else -> slow
                }
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        val first = launch { pipeline.start(AppSettings.default) }
        while (slow.prepareCount == 0) yield()
        val middleSettings = AppSettings.default
        middleSettings.whisperModelVariant = "middle"
        val lastSettings = AppSettings.default
        lastSettings.whisperModelVariant = "last"
        val second = launch { pipeline.restart(middleSettings) }
        repeat(50) { yield() }
        val third = launch { pipeline.restart(lastSettings) }
        repeat(50) { yield() }
        gate.open()
        first.join()
        second.join()
        third.join()
        assertEquals(1, last.prepareCount)
        assertEquals(0, middle.prepareCount)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a start waiting on an abandoned load says the model is loading, and a stop meanwhile is kept`() = runTest {
        val slow = FakeEngine()
        val gate = PrepareGate()
        slow.prepareGate = gate
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to slow))
        val first = launch { pipeline.start(AppSettings.default) }
        while (slow.prepareCount == 0) yield()
        pipeline.stop()
        val second = launch { pipeline.start(AppSettings.default) }
        repeat(50) { yield() }
        assertEquals(
            PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel)),
            pipeline.phase,
        )
        pipeline.stop()
        assertEquals(PipelinePhase.Idle, pipeline.phase)

        gate.open()
        first.join()
        second.join()
        assertEquals(1, slow.prepareCount)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `a start that waits long on an earlier load says it can take minutes too`() = runTest {
        val slow = FakeEngine()
        val gate = PrepareGate()
        slow.prepareGate = gate
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to slow))
        val slowness = PrepareGate()
        pipeline.slowLoadWait = { slowness.wait() }
        val first = launch { pipeline.start(AppSettings.default) }
        while (slow.prepareCount == 0) yield()
        pipeline.stop()
        val second = launch { pipeline.start(AppSettings.default) }
        assertTrue(
            eventually {
                pipeline.phase == PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel))
            },
        )
        slowness.open()
        assertTrue(
            eventually {
                pipeline.phase == PipelinePhase.PreparingEngine(
                    EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, isTakingLong = true),
                )
            },
        )

        gate.open()
        first.join()
        second.join()
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a load that runs long without being a first set-up says it can take minutes, not just a moment`() = runTest {
        val loading = EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "large-v3")
        val engine = FakeEngine(progressUpdates = listOf(loading))
        val gate = PrepareGate()
        engine.afterProgressGate = gate
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        val slowness = PrepareGate()
        pipeline.slowLoadWait = { slowness.wait() }
        val start = launch { pipeline.start(AppSettings.default) }
        assertTrue(eventually { pipeline.phase == PipelinePhase.PreparingEngine(loading) })
        slowness.open()
        val long = loading.copy(isTakingLong = true)
        assertTrue(eventually { pipeline.phase == PipelinePhase.PreparingEngine(long) })
        gate.open()
        start.join()
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a quick load, a download or a first set-up keeps its own wording`() = runTest {
        val firstTime = EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "large-v3", isFirstTime = true)
        val engine = FakeEngine(progressUpdates = listOf(firstTime))
        val gate = PrepareGate()
        engine.afterProgressGate = gate
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.slowLoadWait = { delay(50.milliseconds) }
        val start = launch { pipeline.start(AppSettings.default) }
        assertTrue(eventually { pipeline.phase == PipelinePhase.PreparingEngine(firstTime) })
        delay(200.milliseconds)
        assertEquals(PipelinePhase.PreparingEngine(firstTime), pipeline.phase)
        gate.open()
        start.join()

        pipeline.stop()
        val quick = FakeEngine(progressUpdates = listOf(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, detail = "base")))
        val (fast, _, _) = makePipeline(engines = mapOf(whisper to quick))
        fast.slowLoadWait = { delay(50.milliseconds) }
        fast.start(AppSettings.default)
        delay(150.milliseconds)
        assertEquals(PipelinePhase.Listening, fast.phase)
    }
}
