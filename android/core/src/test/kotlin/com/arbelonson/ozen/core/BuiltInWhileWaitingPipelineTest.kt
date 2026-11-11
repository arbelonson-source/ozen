package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class BuiltInWhileWaitingPipelineTest {
    private class Built {
        val settings = ArrayList<AppSettings>()
    }

    private fun TestScope.pipeline(
        network: FakeNetworkMonitor,
        model: FakeEngine,
        builtIn: FakeEngine,
        built: Built = Built(),
        soundDetector: FakeSoundDetector? = null,
        storage: FakeStorage? = null,
    ): CaptionPipeline {
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                built.settings += settings.copy()
                if (settings.engine == TranscriptionEngineKind.AppleSpeech) builtIn else model
            },
            soundDetector = soundDetector,
            recovery = AutoRecoveryPolicy.disabled(),
            network = network,
            availableStorageBytes = storage?.let { { it.available() } },
        )
        pipeline.networkFirstReportWaitSeconds = 0.3
        pipeline.handOverCheckSeconds = 0.02
        pipeline.switchBackBreathWaitSeconds = 0.3
        return pipeline
    }

    private fun waitingModel() = FakeEngine(TranscriptionEngineKind.WhisperKit).apply {
        pendingDownload = 626
        duringPrepare = { pendingDownload = null }
    }

    private fun builtInEngine() = FakeEngine(TranscriptionEngineKind.AppleSpeech)

    private val chosen: AppSettings
        get() = AppSettings.default.apply {
            engine = TranscriptionEngineKind.WhisperKit
            allowServerFallbackForAppleSpeech = true
        }

    private fun TestScope.line(id: UUID, text: String, final: Boolean) =
        TranscriptToken(utteranceID = id, text = text, isFinal = final, timestamp = virtualNow())

    private suspend fun onBuiltIn(pipeline: CaptionPipeline) {
        pipeline.start(chosen)
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, pipeline.phase.failure?.engineUnavailability?.kind)
        pipeline.captionWithBuiltInWhileWaiting(chosen)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeEngineKind)
    }

    @Test
    fun `on cellular, captions start now on the built-in recognition, on the phone only, and nothing downloads`() = runTest {
        val model = waitingModel()
        val built = Built()
        val pipeline = pipeline(FakeNetworkMonitor(NetworkConditions.cellular), model, builtInEngine(), built)
        onBuiltIn(pipeline)

        assertTrue(pipeline.isCoveringForCloud)
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, pipeline.coverReason)
        val builtInSettings = built.settings.filter { it.engine == TranscriptionEngineKind.AppleSpeech }
        assertTrue(builtInSettings.isNotEmpty())
        assertTrue(builtInSettings.none { it.allowServerFallbackForAppleSpeech }, "the built-in was allowed to send audio to a server")
        assertEquals(0, model.prepareCount)
        assertFalse(pipeline.waitingModelGettingReady)
    }

    @Test
    fun `a built-in recognition that can't run here leaves captions waiting for Wi-Fi, still listening for sounds`() = runTest {
        val builtIn = FakeEngine(
            TranscriptionEngineKind.AppleSpeech,
            availability = EngineAvailability.Unavailable(EngineUnavailability(EngineUnavailability.Kind.LanguageNotSupportedOnDevice, "no on-device Hebrew")),
        )
        val pipeline = pipeline(FakeNetworkMonitor(NetworkConditions.cellular), waitingModel(), builtIn, soundDetector = FakeSoundDetector())
        pipeline.start(chosen)
        assertTrue(eventually { pipeline.isListeningForSoundsOnly })

        pipeline.captionWithBuiltInWhileWaiting(chosen)
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, pipeline.phase.failure?.engineUnavailability?.kind)
        assertFalse(pipeline.isCoveringForCloud)
        assertEquals(TranscriptionEngineKind.WhisperKit, pipeline.activeSettings?.engine)
        assertTrue(pipeline.isListeningForSoundsOnly)
    }

    @Test
    fun `once Wi-Fi is back the model gets ready behind the built-in, then captions switch to it by themselves`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        val builtIn = builtInEngine()
        val gate = PrepareGate()
        model.prepareGate = gate
        val built = Built()
        val pipeline = pipeline(network, model, builtIn, built)
        onBuiltIn(pipeline)
        builtIn.emit(line(UUID.randomUUID(), "said before the model came", final = true))
        assertTrue(eventually { pipeline.segments.size == 1 })

        network.change(NetworkConditions.wifi)
        assertTrue(eventually { model.heldPrepares == 1 })
        assertTrue(pipeline.waitingModelGettingReady)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeEngineKind)
        assertEquals(listOf(false), model.cellularAllowedSeen)
        val modelsMade = built.settings.count { it.engine == TranscriptionEngineKind.WhisperKit }
        val releases = model.releaseCount

        gate.open()
        assertTrue(eventually { pipeline.activeEngineKind == TranscriptionEngineKind.WhisperKit && pipeline.phase == PipelinePhase.Listening })
        assertFalse(pipeline.isCoveringForCloud)
        assertEquals(1, pipeline.builtInHandOvers)
        assertFalse(pipeline.waitingModelGettingReady)
        assertEquals(listOf("said before the model came"), pipeline.segments.map { it.text })
        assertEquals(modelsMade, built.settings.count { it.engine == TranscriptionEngineKind.WhisperKit }, "the model was made again to take over")
        assertEquals(releases, model.releaseCount, "the ready model was let go on its way to taking over")
    }

    @Test
    fun `the switch waits for the line being said to finish`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        val builtIn = builtInEngine()
        val pipeline = pipeline(network, model, builtIn)
        onBuiltIn(pipeline)
        val id = UUID.randomUUID()
        builtIn.emit(line(id, "still talking", final = false))
        assertTrue(eventually { pipeline.segments.size == 1 })

        network.change(NetworkConditions.wifi)
        assertTrue(eventually { model.prepareCount >= 1 && !pipeline.waitingModelGettingReady })
        delay(400.milliseconds)
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeEngineKind, "switched in the middle of a line")

        builtIn.emit(line(id, "still talking, and done", final = true))
        assertTrue(eventually { pipeline.activeEngineKind == TranscriptionEngineKind.WhisperKit && pipeline.phase == PipelinePhase.Listening })
        assertEquals(1, pipeline.builtInHandOvers)
    }

    @Test
    fun `a model that fails to get ready leaves the built-in captioning, and is tried again when the connection changes`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        model.duringPrepare = null
        model.availability = EngineAvailability.Unavailable(EngineUnavailability(EngineUnavailability.Kind.ModelDownloadFailed, "cut off"))
        val pipeline = pipeline(network, model, builtInEngine())
        pipeline.waitingModelRetrySeconds = 600.0
        onBuiltIn(pipeline)

        network.change(NetworkConditions.wifi)
        assertTrue(eventually { model.prepareCount == 1 && !pipeline.waitingModelGettingReady })
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeEngineKind)
        assertTrue(pipeline.isCoveringForCloud)

        model.availability = EngineAvailability.Available
        model.duringPrepare = { model.pendingDownload = null }
        network.change(NetworkConditions.cellular)
        network.change(NetworkConditions.wifi)
        assertTrue(eventually { pipeline.activeEngineKind == TranscriptionEngineKind.WhisperKit && pipeline.phase == PipelinePhase.Listening })
        assertEquals(1, pipeline.builtInHandOvers)
    }

    @Test
    fun `a model that fails to get ready on Wi-Fi is tried again after a while without a change of connection`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        model.duringPrepare = null
        model.availability = EngineAvailability.Unavailable(EngineUnavailability(EngineUnavailability.Kind.ModelDownloadFailed, "server hiccup"))
        val pipeline = pipeline(network, model, builtInEngine())
        pipeline.waitingModelRetrySeconds = 0.2
        onBuiltIn(pipeline)

        network.change(NetworkConditions.wifi)
        assertTrue(eventually { model.prepareCount == 1 && !pipeline.waitingModelGettingReady })
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeEngineKind)

        model.availability = EngineAvailability.Available
        model.duringPrepare = { model.pendingDownload = null }
        assertTrue(eventually { pipeline.activeEngineKind == TranscriptionEngineKind.WhisperKit && pipeline.phase == PipelinePhase.Listening })
        assertTrue(model.prepareCount >= 2)
    }

    @Test
    fun `a phone too full for the model keeps the built-in captioning and starts no download, and a return to the app with room gets it ready`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        val storage = FakeStorage(300)
        val pipeline = pipeline(network, model, builtInEngine(), storage = storage)
        pipeline.waitingModelRetrySeconds = 0.1
        onBuiltIn(pipeline)

        network.change(NetworkConditions.wifi)
        assertTrue(eventually { model.pendingDownloadChecks >= 2 && !pipeline.waitingModelGettingReady })
        delay(400.milliseconds)
        assertEquals(0, model.prepareCount)
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeEngineKind)
        assertEquals(PipelinePhase.Listening, pipeline.phase)

        storage.set(20_000)
        pipeline.appDidBecomeActive()
        assertTrue(eventually { pipeline.activeEngineKind == TranscriptionEngineKind.WhisperKit && pipeline.phase == PipelinePhase.Listening })
        assertEquals(1, pipeline.builtInHandOvers)
    }

    @Test
    fun `stopping captions ends the built-in, and Wi-Fi afterwards gets nothing ready`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        val pipeline = pipeline(network, model, builtInEngine())
        onBuiltIn(pipeline)

        pipeline.stop()
        network.change(NetworkConditions.wifi)
        delay(200.milliseconds)
        assertEquals(0, model.prepareCount)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `stopping while the model gets ready stops its download and lets it go`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        val gate = PrepareGate()
        model.prepareGate = gate
        val pipeline = pipeline(network, model, builtInEngine())
        onBuiltIn(pipeline)
        network.change(NetworkConditions.wifi)
        assertTrue(eventually { model.heldPrepares == 1 })
        val releases = model.releaseCount

        pipeline.stop()
        assertTrue(eventually { model.downloadCancels == 1 })
        gate.open()
        assertTrue(eventually { model.releaseCount == releases + 1 })
        delay(200.milliseconds)
        assertEquals(releases + 1, model.releaseCount, "let go twice")
        assertFalse(pipeline.waitingModelGettingReady)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
    }

    @Test
    fun `turning cellular downloads off while the model downloads behind the built-in reaches that download`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        val gate = PrepareGate()
        model.prepareGate = gate
        val pipeline = pipeline(network, model, builtInEngine())
        onBuiltIn(pipeline)

        pipeline.setAllowCellularModelDownload(true)
        assertTrue(eventually { model.heldPrepares == 1 })
        assertEquals(listOf(true), model.cellularAllowedSeen)

        pipeline.setAllowCellularModelDownload(false)
        assertEquals(listOf(true, false), model.cellularAllowedSeen, "the download kept its permission for cellular")
        gate.open()
    }

    @Test
    fun `a pause and resume keep the built-in while the model is not ready`() = runTest {
        val pipeline = pipeline(FakeNetworkMonitor(NetworkConditions.cellular), waitingModel(), builtInEngine())
        onBuiltIn(pipeline)

        pipeline.pause()
        pipeline.resume(chosen)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(TranscriptionEngineKind.AppleSpeech, pipeline.activeEngineKind)
        assertTrue(pipeline.isCoveringForCloud)
    }

    @Test
    fun `with the model ready while paused, a resume starts the model itself`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        val built = Built()
        val pipeline = pipeline(network, model, builtInEngine(), built)
        onBuiltIn(pipeline)

        pipeline.pause()
        network.change(NetworkConditions.wifi)
        assertTrue(eventually { model.prepareCount == 1 && !pipeline.waitingModelGettingReady })
        delay(100.milliseconds)
        assertEquals(PipelinePhase.Paused, pipeline.phase)
        val modelsMade = built.settings.count { it.engine == TranscriptionEngineKind.WhisperKit }
        val releases = model.releaseCount

        pipeline.resume(chosen)
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertEquals(TranscriptionEngineKind.WhisperKit, pipeline.activeEngineKind)
        assertFalse(pipeline.isCoveringForCloud)
        assertEquals(modelsMade, built.settings.count { it.engine == TranscriptionEngineKind.WhisperKit }, "the ready model was made again")
        assertEquals(releases, model.releaseCount, "the ready model was let go")
    }

    @Test
    fun `starting the phone's model while it gets ready behind the built-in waits for it instead of preparing it twice`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val model = waitingModel()
        val gate = PrepareGate()
        model.prepareGate = gate
        val pipeline = pipeline(network, model, builtInEngine())
        onBuiltIn(pipeline)
        network.change(NetworkConditions.wifi)
        assertTrue(eventually { model.heldPrepares == 1 })

        val restarting = async { pipeline.restart(chosen) }
        delay(150.milliseconds)
        assertEquals(1, model.heldPrepares, "the same model was prepared twice at once")

        gate.open()
        restarting.await()
        assertTrue(eventually { pipeline.activeEngineKind == TranscriptionEngineKind.WhisperKit && pipeline.phase == PipelinePhase.Listening })
    }
}
