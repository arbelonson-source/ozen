package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private fun TestScope.makeNetworkPipeline(
    network: FakeNetworkMonitor?,
    pendingDownload: Int? = 626,
    recovery: AutoRecoveryPolicy = AutoRecoveryPolicy.disabled(),
    soundDetector: FakeSoundDetector? = null,
): Pair<CaptionPipeline, FakeEngine> {
    val engine = FakeEngine()
    engine.pendingDownload = pendingDownload
    val pipeline = captionPipeline(
        audio = FakeAudioCapturer(),
        engineFactory = { engine },
        embedder = FakeEmbedder(),
        soundDetector = soundDetector,
        recovery = recovery,
        network = network,
    )
    pipeline.networkFirstReportWaitSeconds = 0.3
    return pipeline to engine
}

private fun cellularSettings(allowCellular: Boolean = false): AppSettings {
    val settings = AppSettings.default
    settings.allowCellularModelDownload = allowCellular
    return settings
}

private fun TestScope.directPipeline(
    engine: FakeEngine,
    network: FakeNetworkMonitor,
    recovery: AutoRecoveryPolicy = AutoRecoveryPolicy.disabled(),
): CaptionPipeline = captionPipeline(
    audio = FakeAudioCapturer(),
    engineFactory = { engine },
    embedder = FakeEmbedder(),
    recovery = recovery,
    network = network,
)

class CaptionPipelineDownloadNetworkTest {
    @Test
    fun `on cellular, a model that still has to download waits for Wi-Fi and says how big it is`() = runTest {
        val (pipeline, engine) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions.cellular), recovery = AutoRecoveryPolicy())
        pipeline.start(cellularSettings())

        val why = pipeline.phase.failure?.engineUnavailability
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, why?.kind)
        assertEquals(626, why?.downloadMegabytes)
        assertEquals(0, engine.prepareCount)
        // No timer keeps asking the same cellular connection.
        assertNull(pipeline.scheduledRetry)
        assertEquals(true, pipeline.phase.failure?.isRetryableInApp)
        assertEquals(false, pipeline.phase.failure?.suggestsOtherEngine)
    }

    @Test
    fun `a network the system reports only after captions start is still respected, cellular waits`() = runTest {
        val monitor = FakeNetworkMonitor(null)
        val (pipeline, engine) = makeNetworkPipeline(network = monitor)
        // The wait ends as soon as the report comes; a short one lost the
        // race on a busy machine running every suite at once.
        pipeline.networkFirstReportWaitSeconds = 5.0
        launch {
            delay(100.milliseconds)
            monitor.current = NetworkConditions.cellular
        }
        pipeline.start(cellularSettings())
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, pipeline.phase.failure?.engineUnavailability?.kind)
        assertEquals(0, engine.prepareCount)
    }

    @Test
    fun `Low Data Mode waits the same way`() = runTest {
        val (pipeline, _) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions(isConnected = true, isConstrained = true)))
        pipeline.start(cellularSettings())
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, pipeline.phase.failure?.engineUnavailability?.kind)
    }

    @Test
    fun `on Wi-Fi, with nothing to download, or before the system has reported, it goes ahead`() = runTest {
        val cases = listOf(
            FakeNetworkMonitor(NetworkConditions.wifi) to (626 as Int?),
            FakeNetworkMonitor(NetworkConditions.cellular) to null,
            FakeNetworkMonitor(null) to 626,
        )
        for ((network, pending) in cases) {
            val (pipeline, engine) = makeNetworkPipeline(network = network, pendingDownload = pending)
            pipeline.start(cellularSettings())
            assertTrue(pipeline.phase.isListening)
            assertEquals(1, engine.prepareCount)
        }
        val (unmonitored, _) = makeNetworkPipeline(network = null)
        unmonitored.start(cellularSettings())
        assertTrue(unmonitored.phase.isListening)
    }

    @Test
    fun `with no connection at all it fails as a download problem without trying`() = runTest {
        val (pipeline, engine) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions.offline))
        pipeline.start(cellularSettings())
        assertEquals(EngineUnavailability.Kind.ModelDownloadFailed, pipeline.phase.failure?.engineUnavailability?.kind)
        assertEquals(0, engine.prepareCount)
    }

    @Test
    fun `the setting to allow cellular downloads lets it go ahead`() = runTest {
        val (pipeline, _) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions.cellular))
        pipeline.start(cellularSettings(allowCellular = true))
        assertTrue(pipeline.phase.isListening)
    }

    @Test
    fun `download now anyway starts the download over cellular`() = runTest {
        val (pipeline, engine) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions.cellular))
        pipeline.start(cellularSettings())
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, pipeline.phase.failure?.engineUnavailability?.kind)

        pipeline.approveCellularDownload()
        assertTrue(pipeline.phase.isListening)
        assertEquals(1, engine.prepareCount)
    }

    @Test
    fun `turning on the setting while waiting starts the download`() = runTest {
        val (pipeline, _) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions.cellular))
        pipeline.start(cellularSettings())
        pipeline.setAllowCellularModelDownload(true)
        assertTrue(pipeline.phase.isListening)
    }

    @Test
    fun `the engine hears whether its download may use cellular, not on Wi-Fi alone, yes once she says download now`() = runTest {
        val (pipeline, engine) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions.wifi))
        pipeline.start(cellularSettings())
        assertEquals(listOf(false), engine.cellularAllowedSeen)

        val (onCellular, waiting) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions.cellular))
        onCellular.start(cellularSettings())
        onCellular.approveCellularDownload()
        assertEquals(listOf(true), waiting.cellularAllowedSeen)

        val (allowed, allowedEngine) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions.wifi))
        allowed.start(cellularSettings(allowCellular = true))
        assertEquals(listOf(true), allowedEngine.cellularAllowedSeen)
    }

    @Test
    fun `a download cut off when Wi-Fi dropped waits for Wi-Fi, not on the phone plan, and goes on when Wi-Fi is back`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.wifi)
        val (pipeline, engine) = makeNetworkPipeline(network = network)
        engine.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.ModelDownloadFailed, "The Internet connection appears to be offline.")
        engine.duringPrepare = { network.change(NetworkConditions.cellular) }
        pipeline.start(cellularSettings())

        val why = pipeline.phase.failure?.engineUnavailability
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, why?.kind)
        assertEquals(626, why?.downloadMegabytes)
        assertNull(pipeline.scheduledRetry)

        engine.duringPrepare = null
        engine.availability = EngineAvailability.Available
        network.change(NetworkConditions.wifi)
        assertTrue(eventually { pipeline.phase.isListening })
        assertEquals(2, engine.prepareCount)
    }

    @Test
    fun `a failed load with nothing left to download is retried on its timer even on cellular, not shown as waiting for Wi-Fi`() = runTest {
        val (pipeline, engine) = makeNetworkPipeline(network = FakeNetworkMonitor(NetworkConditions.cellular), pendingDownload = null, recovery = AutoRecoveryPolicy())
        engine.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.ModelDownloadFailed, "tokenizer fetch failed")
        pipeline.start(cellularSettings())

        assertEquals(EngineUnavailability.Kind.ModelDownloadFailed, pipeline.phase.failure?.engineUnavailability?.kind)
        assertNotNull(pipeline.scheduledRetry)
    }

    @Test
    fun `Wi-Fi back by the time a refused download is reported is not a wait for Wi-Fi`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.wifi)
        val (pipeline, engine) = makeNetworkPipeline(network = network, recovery = AutoRecoveryPolicy())
        engine.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.ModelDownloadFailed, "The Internet connection appears to be offline.")
        engine.duringPrepare = {
            network.change(NetworkConditions.cellular)
            engine.duringPendingDownloadCheck = { network.change(NetworkConditions.wifi) }
        }
        pipeline.start(cellularSettings())

        assertEquals(EngineUnavailability.Kind.ModelDownloadFailed, pipeline.phase.failure?.engineUnavailability?.kind)
        assertNotNull(pipeline.scheduledRetry)
    }

    @Test
    fun `cellular downloads switched on while the model downloads means a later Wi-Fi drop does not stop for Wi-Fi`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.wifi)
        val (pipeline, engine) = makeNetworkPipeline(network = network, recovery = AutoRecoveryPolicy())
        val gate = PrepareGate()
        engine.prepareGate = gate
        engine.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.ModelDownloadFailed, "The Internet connection appears to be offline.")
        engine.duringPrepare = { network.change(NetworkConditions.cellular) }
        val starting = launch { pipeline.start(cellularSettings()) }
        assertTrue(eventually { engine.prepareCount == 1 })
        pipeline.setAllowCellularModelDownload(true)
        gate.open()
        starting.join()

        assertEquals(EngineUnavailability.Kind.ModelDownloadFailed, pipeline.phase.failure?.engineUnavailability?.kind)
        assertNotNull(pipeline.scheduledRetry)
    }

    @Test
    fun `while a download waits for Wi-Fi the microphone stays on for sound alerts, until Wi-Fi brings captions back`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val (pipeline, _) = makeNetworkPipeline(network = network, soundDetector = FakeSoundDetector())
        pipeline.start(cellularSettings())
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, pipeline.phase.failure?.engineUnavailability?.kind)
        assertTrue(pipeline.isListeningForSoundsOnly)

        network.change(NetworkConditions.wifi)
        assertTrue(eventually { pipeline.phase.isListening })
        assertFalse(pipeline.isListeningForSoundsOnly)
    }

    @Test
    fun `reaching Wi-Fi starts a waiting download by itself`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val (pipeline, engine) = makeNetworkPipeline(network = network)
        pipeline.start(cellularSettings())

        network.change(NetworkConditions.cellular)
        assertEquals(0, engine.prepareCount)

        network.change(NetworkConditions.wifi)
        assertTrue(eventually { pipeline.phase.isListening })
        assertEquals(1, engine.prepareCount)
    }

    @Test
    fun `a download that failed offline starts again when the connection comes back`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.offline)
        val (pipeline, _) = makeNetworkPipeline(network = network)
        pipeline.start(cellularSettings())
        assertNotNull(pipeline.phase.failure)

        network.change(NetworkConditions.wifi)
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `with the phone plan allowed, a download that failed offline starts again when mobile data comes back`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.offline)
        val (pipeline, _) = makeNetworkPipeline(network = network)
        pipeline.start(cellularSettings(allowCellular = true))
        assertEquals(EngineUnavailability.Kind.ModelDownloadFailed, pipeline.phase.failure?.engineUnavailability?.kind)

        network.change(NetworkConditions.cellular)
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `a download that failed on Wi-Fi isn't retried early just because Wi-Fi reported again`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.wifi)
        val engine = FakeEngine(availability = EngineAvailability.unavailable(EngineUnavailability.Kind.ModelDownloadFailed, "server said no"))
        engine.pendingDownload = 626
        val pipeline = directPipeline(engine, network)
        pipeline.start(cellularSettings())
        assertEquals(1, engine.prepareCount)

        network.change(NetworkConditions.wifi)
        delay(50.milliseconds)
        assertEquals(1, engine.prepareCount)
    }

    private suspend fun TestScope.streamingReturnsWithConnection(kind: EngineUnavailability.Kind) {
        val network = FakeNetworkMonitor(NetworkConditions.offline)
        val engine = FakeEngine(availability = EngineAvailability.unavailable(kind, "offline"))
        val pipeline = directPipeline(engine, network)
        pipeline.start(cellularSettings())
        assertEquals(kind, pipeline.phase.failure?.engineUnavailability?.kind)
        assertEquals(1, engine.prepareCount)

        network.change(NetworkConditions.offline)
        delay(50.milliseconds)
        assertEquals(1, engine.prepareCount)

        engine.availability = EngineAvailability.Available
        network.change(NetworkConditions.cellular)
        assertTrue(eventually { pipeline.phase.isListening })
        assertEquals(2, engine.prepareCount)
    }

    @Test
    fun `captions that stopped for want of the internet come back when it does, over cellular too - noInternet`() = runTest {
        streamingReturnsWithConnection(EngineUnavailability.Kind.NoInternet)
    }

    @Test
    fun `captions that stopped for want of the internet come back when it does, over cellular too - homeServerUnreachable`() = runTest {
        streamingReturnsWithConnection(EngineUnavailability.Kind.HomeServerUnreachable)
    }

    private suspend fun TestScope.connectionReturnsDuringCall(kind: EngineUnavailability.Kind) {
        val network = FakeNetworkMonitor(NetworkConditions.offline)
        val engine = FakeEngine(availability = EngineAvailability.unavailable(kind, "offline"))
        val pipeline = directPipeline(engine, network, AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList()))
        pipeline.systemInterruptionChanged(true)
        pipeline.start(cellularSettings())
        assertEquals(kind, pipeline.phase.failure?.engineUnavailability?.kind)

        engine.availability = EngineAvailability.Available
        network.change(NetworkConditions.wifi)
        delay(100.milliseconds)
        assertEquals(1, engine.prepareCount, "the connection coming back took the microphone during the call")

        pipeline.systemInterruptionChanged(false)
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `captions that stopped for want of the internet wait out a phone call before the connection brings them back - noInternet`() = runTest {
        connectionReturnsDuringCall(EngineUnavailability.Kind.NoInternet)
    }

    @Test
    fun `captions that stopped for want of the internet wait out a phone call before the connection brings them back - homeServerUnreachable`() = runTest {
        connectionReturnsDuringCall(EngineUnavailability.Kind.HomeServerUnreachable)
    }

    @Test
    fun `captions whose quick retries ran out get a fresh set when the connection comes back, so a first try that fails isn't the last`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.offline)
        val engine = FakeEngine(availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "offline"))
        val pipeline = directPipeline(engine, network, AutoRecoveryPolicy(glitchDelays = listOf(0.3), downloadDelays = emptyList()))
        pipeline.start(cellularSettings())
        assertTrue(eventually { engine.prepareCount == 2 && pipeline.phase.failure != null && pipeline.scheduledRetry == null })

        // Back on Wi-Fi, but the first try still fails: the router is
        // still coming up.
        network.change(NetworkConditions.wifi)
        assertTrue(eventually { engine.prepareCount == 3 && pipeline.phase.failure != null })
        engine.availability = EngineAvailability.Available
        assertTrue(eventually { pipeline.phase.isListening })
    }

    @Test
    fun `Wi-Fi that came back during a phone call starts the waiting download when the call ends, still on cellular, it keeps waiting`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.cellular)
        val (pipeline, engine) = makeNetworkPipeline(network = network, recovery = AutoRecoveryPolicy())
        pipeline.start(cellularSettings())
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, pipeline.phase.failure?.engineUnavailability?.kind)

        pipeline.systemInterruptionChanged(true)
        pipeline.systemInterruptionChanged(false)
        delay(100.milliseconds)
        assertEquals(0, engine.prepareCount)
        assertEquals(EngineUnavailability.Kind.WaitingForWiFi, pipeline.phase.failure?.engineUnavailability?.kind)

        pipeline.systemInterruptionChanged(true)
        network.change(NetworkConditions.wifi)
        delay(100.milliseconds)
        assertEquals(0, engine.prepareCount, "the download took the microphone during the call")

        pipeline.systemInterruptionChanged(false)
        assertTrue(eventually { pipeline.phase.isListening })
        assertEquals(1, engine.prepareCount)
    }

    @Test
    fun `a connection change doesn't touch captions that are running or failed for other reasons`() = runTest {
        val network = FakeNetworkMonitor(NetworkConditions.offline)
        val (pipeline, engine) = makeNetworkPipeline(network = network, pendingDownload = null)
        pipeline.start(cellularSettings())
        assertTrue(pipeline.phase.isListening)

        engine.endStream(throwing = TestError())
        eventually { pipeline.phase.failure != null }
        network.change(NetworkConditions.wifi)
        delay(50.milliseconds)
        assertEquals(PipelineFailure.Kind.TranscriptionStopped, pipeline.phase.failure?.kind)
        assertEquals(1, engine.prepareCount)
    }

    private suspend fun TestScope.personNeededIgnoresConnection(kind: EngineUnavailability.Kind) {
        val network = FakeNetworkMonitor(NetworkConditions.offline)
        val engine = FakeEngine(availability = EngineAvailability.unavailable(kind, "needs a person"))
        val pipeline = directPipeline(engine, network)
        pipeline.start(cellularSettings())
        assertEquals(kind, pipeline.phase.failure?.engineUnavailability?.kind)
        assertEquals(1, engine.prepareCount)

        engine.availability = EngineAvailability.Available
        network.change(NetworkConditions.wifi)
        delay(50.milliseconds)
        assertEquals(1, engine.prepareCount)
        assertEquals(kind, pipeline.phase.failure?.engineUnavailability?.kind)
    }

    @Test
    fun `a stop only a person can fix isn't tried again because the internet came back - homeServerRejected`() = runTest {
        personNeededIgnoresConnection(EngineUnavailability.Kind.HomeServerRejected)
    }

    @Test
    fun `a stop only a person can fix isn't tried again because the internet came back - cloudKeyNeeded`() = runTest {
        personNeededIgnoresConnection(EngineUnavailability.Kind.CloudKeyNeeded)
    }

    @Test
    fun `a stop only a person can fix isn't tried again because the internet came back - cloudOutOfCredit`() = runTest {
        personNeededIgnoresConnection(EngineUnavailability.Kind.CloudOutOfCredit)
    }

    @Test
    fun `a stop only a person can fix isn't tried again because the internet came back - modelLoadFailed`() = runTest {
        personNeededIgnoresConnection(EngineUnavailability.Kind.ModelLoadFailed)
    }
}

class NaNEmbedder : SpeakerEmbedding {
    override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? = floatArrayOf(Float.NaN, 0f, 0f)
}

class CaptionPipelineSilencePhraseTest {
    @Test
    fun `thanks invented again and again while nobody talks shows once, and real speech after it shows`() = runTest {
        val engine = FakeEngine()
        val pipeline = captionPipeline(audio = FakeAudioCapturer(), engineFactory = { engine }, embedder = FakeEmbedder(), recovery = AutoRecoveryPolicy.disabled())
        pipeline.start(AppSettings.default)
        repeat(4) {
            engine.emit(TranscriptToken(utteranceID = UUID.randomUUID(), text = "תודה.", isFinal = true, timestamp = 1.0))
        }
        engine.emit(TranscriptToken(utteranceID = UUID.randomUUID(), text = "תודה. תודה. תודה.", isFinal = true, timestamp = 2.0))
        engine.emit(TranscriptToken(utteranceID = UUID.randomUUID(), text = "מה שלומך היום?", isFinal = true, timestamp = 3.0))
        assertTrue(eventually { pipeline.segments.map { it.text } == listOf("תודה.", "מה שלומך היום?") })
    }

    @Test
    fun `a final suppressed as a repeated thanks still commits the words already shown, without waiting on the stale-commit safety net`() = runTest {
        val engine = FakeEngine()
        // The clock agrees with the tokens' times: against today's date every
        // line looked decades quiet, so on a slow CI machine the stale-commit
        // safety net finished it before the final and counted it twice.
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { engine },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
            now = { 1.0 },
        )
        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()
        engine.emit(TranscriptToken(utteranceID = id, text = "תודה", isFinal = false, timestamp = 0.0))
        assertTrue(eventually { pipeline.segments.size == 1 })
        engine.emit(TranscriptToken(utteranceID = id, text = "תודה. תודה.", isFinal = true, timestamp = 1.0))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })
        assertEquals("תודה", pipeline.segments.firstOrNull()?.text)
        // Counted as finished, so VoiceOver reads it now, not when the
        // next line ends.
        assertEquals(1, pipeline.committedLineCount)
        assertEquals(1, pipeline.stats.segmentsCommitted)
    }
}

class CaptionPipelineNaNEmbeddingTest {
    @Test
    fun `a voice print full of NaNs is skipped instead of opening a phantom speaker`() = runTest {
        val audio = FakeAudioCapturer()
        val pipeline = captionPipeline(audio = audio, engineFactory = { FakeEngine() }, embedder = NaNEmbedder(), recovery = AutoRecoveryPolicy.disabled())
        pipeline.start(AppSettings.default)
        audio.push(FloatArray(48_000) { 0.5f })
        assertTrue(eventually { pipeline.stats.audioChunksReceived == 1 })
        delay(100.milliseconds)
        assertTrue(pipeline.speakerClusters.isEmpty())
    }

    @Test
    fun `damaged audio from the microphone is counted for the diagnostics report`() = runTest {
        val audio = FakeAudioCapturer()
        val pipeline = captionPipeline(audio = audio, engineFactory = { FakeEngine() }, embedder = FakeEmbedder(), recovery = AutoRecoveryPolicy.disabled())
        pipeline.start(AppSettings.default)
        val glitched = FloatArray(1_600) { 0.1f }
        glitched[7] = Float.NaN
        audio.push(glitched)
        audio.push(FloatArray(1_600) { 0.1f })
        audio.push(glitched)
        assertTrue(eventually { pipeline.stats.audioChunksReceived == 3 })
        assertTrue(eventually { pipeline.stats.glitchedAudioChunks == 2 })
    }
}
