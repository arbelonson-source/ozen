package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private data class StoragePipeline(val pipeline: CaptionPipeline, val engine: FakeEngine, val audio: FakeAudioCapturer)

private fun TestScope.makeStoragePipeline(
    storage: FakeStorage?,
    pendingDownload: Int? = 626,
    soundDetector: FakeSoundDetector? = null,
): StoragePipeline {
    val engine = FakeEngine()
    val audio = FakeAudioCapturer()
    engine.pendingDownload = pendingDownload
    val freeSpace: (() -> Long?)? = storage?.let { { it.available() } }
    val pipeline = captionPipeline(
        audio = audio,
        engineFactory = { engine },
        embedder = FakeEmbedder(),
        soundDetector = soundDetector,
        recovery = AutoRecoveryPolicy(),
        network = FakeNetworkMonitor(NetworkConditions.wifi),
        availableStorageBytes = freeSpace,
    )
    return StoragePipeline(pipeline, engine, audio)
}

class CaptionPipelineStorageTest {
    @Test
    fun `a phone without room for the model says so, with how much to free, and doesn't start the download`() = runTest {
        val storage = FakeStorage(300)
        val (pipeline, engine, _) = makeStoragePipeline(storage = storage)
        pipeline.start(AppSettings.default)

        val why = pipeline.phase.failure?.engineUnavailability
        assertEquals(EngineUnavailability.Kind.NotEnoughStorage, why?.kind)
        assertEquals(626, why?.downloadMegabytes)
        assertEquals(StorageSpaceGate.requiredMegabytes(626) - 300, why?.missingMegabytes)
        assertEquals(0, engine.prepareCount)
        // Retrying on a timer can't free up space.
        assertNull(pipeline.scheduledRetry)
        assertEquals(true, pipeline.phase.failure?.suggestsOtherEngine)
        assertEquals(true, pipeline.phase.failure?.isRetryableInApp)
    }

    @Test
    fun `while the model waits for room on the phone the microphone stays on for sound alerts`() = runTest {
        val (pipeline, _, _) = makeStoragePipeline(storage = FakeStorage(300), soundDetector = FakeSoundDetector())
        pipeline.start(AppSettings.default)
        assertEquals(EngineUnavailability.Kind.NotEnoughStorage, pipeline.phase.failure?.engineUnavailability?.kind)
        assertTrue(pipeline.isListeningForSoundsOnly)
    }

    @Test
    fun `enough room, nothing to download, an unknown size, or no way to check - it goes ahead`() = runTest {
        val cases: List<Pair<FakeStorage?, Int?>> = listOf(
            FakeStorage(20_000) to 626,
            FakeStorage(10) to null,
            FakeStorage(10) to 0,
            FakeStorage(null) to 626,
            null to 626,
        )
        for ((storage, pending) in cases) {
            val (pipeline, engine, _) = makeStoragePipeline(storage = storage, pendingDownload = pending)
            pipeline.start(AppSettings.default)
            assertTrue(pipeline.phase.isListening)
            assertEquals(1, engine.prepareCount)
        }
    }

    @Test
    fun `a model compiled on the phone needs room for twice its download, and a return to the app waits for that much`() = runTest {
        val storage = FakeStorage(1_400)
        val (pipeline, engine, _) = makeStoragePipeline(storage = storage, pendingDownload = 819)
        engine.pendingInstall = 1_638
        pipeline.start(AppSettings.default)
        val why = pipeline.phase.failure?.engineUnavailability
        assertEquals(EngineUnavailability.Kind.NotEnoughStorage, why?.kind)
        assertEquals(StorageSpaceGate.requiredMegabytes(1_638) - 1_400, why?.missingMegabytes)
        assertEquals(0, engine.prepareCount)

        storage.set((StorageSpaceGate.requiredMegabytes(819) + 10).toLong())
        pipeline.appDidBecomeActive()
        assertEquals(0, engine.prepareCount)

        storage.set((StorageSpaceGate.requiredMegabytes(1_638) + 10).toLong())
        pipeline.appDidBecomeActive()
        assertTrue(pipeline.phase.isListening)
    }

    @Test
    fun `coming back to the app after freeing up room starts the download by itself`() = runTest {
        val storage = FakeStorage(300)
        val (pipeline, engine, audio) = makeStoragePipeline(storage = storage)
        pipeline.start(AppSettings.default)
        assertEquals(EngineUnavailability.Kind.NotEnoughStorage, pipeline.phase.failure?.engineUnavailability?.kind)

        // Back on screen without having freed anything: stays put, quietly,
        // without even restarting the microphone to find out again.
        val callsBefore = audio.calls.size
        pipeline.appDidBecomeActive()
        assertEquals(EngineUnavailability.Kind.NotEnoughStorage, pipeline.phase.failure?.engineUnavailability?.kind)
        assertEquals(0, engine.prepareCount)
        assertEquals(callsBefore, audio.calls.size)

        storage.set(20_000)
        pipeline.appDidBecomeActive()
        assertTrue(pipeline.phase.isListening)
        assertEquals(1, engine.prepareCount)
    }

    @Test
    fun `coming back to the app leaves every other state alone`() = runTest {
        val (listening, engine, _) = makeStoragePipeline(storage = FakeStorage(20_000))
        listening.start(AppSettings.default)
        listening.appDidBecomeActive()
        assertTrue(listening.phase.isListening)
        assertEquals(1, engine.prepareCount)

        val (idle, idleEngine, _) = makeStoragePipeline(storage = FakeStorage(20_000))
        idle.appDidBecomeActive()
        assertEquals(PipelinePhase.Idle, idle.phase)
        assertEquals(0, idleEngine.prepareCount)
    }
}

private data class OpenLine(val pipeline: CaptionPipeline, val engine: FakeEngine, val utterance: UUID)

private suspend fun TestScope.startWithOpenLine(): OpenLine {
    val engine = FakeEngine()
    val pipeline = captionPipeline(
        audio = FakeAudioCapturer(),
        engineFactory = { engine },
        embedder = FakeEmbedder(),
        recovery = AutoRecoveryPolicy.disabled(),
        audioWatchdog = AudioStallWatchdog.disabled,
    )
    pipeline.start(AppSettings.default)
    val utterance = UUID.randomUUID()
    engine.emit(TranscriptToken(utteranceID = utterance, text = "הרופא אמר ש", isFinal = false, timestamp = virtualNow()))
    eventually { pipeline.segments.size == 1 }
    return OpenLine(pipeline, engine, utterance)
}

class CaptionPipelineOpenLineTest {
    @Test
    fun `pausing finishes the line that was being written`() = runTest {
        val (pipeline, _, _) = startWithOpenLine()
        assertEquals(false, pipeline.segments.firstOrNull()?.isCommitted)
        assertTrue(pipeline.stats.hasOpenLine)

        pipeline.pause()
        assertEquals(true, pipeline.segments.firstOrNull()?.isCommitted)
        assertEquals("הרופא אמר ש" + CaptionStabilizer.CUT_OFF_MARK, pipeline.segments.firstOrNull()?.text)
        assertEquals(1, pipeline.stats.segmentsCommitted)
        assertFalse(pipeline.stats.hasOpenLine)
    }

    @Test
    fun `stopping finishes it too, and a line already final isn't counted twice`() = runTest {
        val (pipeline, engine, _) = startWithOpenLine()
        engine.emit(TranscriptToken(utteranceID = UUID.randomUUID(), text = "כן", isFinal = true, timestamp = virtualNow()))
        eventually { pipeline.segments.size == 2 }
        assertEquals(1, pipeline.stats.segmentsCommitted)

        pipeline.stop()
        val allFinished = pipeline.segments.all { it.isCommitted }
        assertTrue(allFinished)
        assertEquals(2, pipeline.stats.segmentsCommitted)
    }
}

class CaptionPipelineRetryGuardTest {
    @Test
    fun `a retry asked for while starting or listening is ignored, so nothing prepares twice`() = runTest {
        val engine = FakeEngine()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { engine },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
            audioWatchdog = AudioStallWatchdog.disabled,
        )
        var phaseWhenAsked: PipelinePhase? = null
        val scope = this
        engine.duringPrepare = {
            phaseWhenAsked = pipeline.phase
            scope.launch { pipeline.retry() }
        }
        pipeline.start(AppSettings.default)
        delay(150.milliseconds)
        assertEquals(true, phaseWhenAsked?.isTransitioning)
        assertTrue(pipeline.phase.isListening)
        assertEquals(1, engine.prepareCount)

        pipeline.retry()
        assertEquals(1, engine.prepareCount)
        assertTrue(pipeline.phase.isListening)
    }
}
