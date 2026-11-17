package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

class CloudCoverHomeSoonerTest {
    private val homeKind = TranscriptionEngineKind.HomeServer

    private fun settings(paired: Boolean = true): AppSettings {
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.Cloud
        if (paired) settings.homeServerAddress = "wss://192.168.1.20:8765"
        return settings
    }

    private fun loadFailure() = EngineAvailability.unavailable(EngineUnavailability.Kind.ModelLoadFailed, "the model would not load")

    private fun homeAnswering(answers: Boolean = true) = FakeEngine(
        kind = homeKind,
        availability = if (answers) {
            EngineAvailability.Available
        } else {
            EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "no answer")
        },
    )

    private fun TestScope.pipelineOf(
        cloud: TranscriptionEngine,
        phone: FakeEngine,
        home: FakeEngine,
        audio: FakeAudioCapturer = FakeAudioCapturer(),
        recovery: AutoRecoveryPolicy = AutoRecoveryPolicy.disabled(),
        now: () -> Double = { virtualNow() },
        newHome: FakeEngine? = null,
    ): CaptionPipeline {
        val captions = captionPipeline(
            audio = audio,
            engineFactory = {
                when (it.engine) {
                    TranscriptionEngineKind.Cloud -> cloud
                    homeKind -> if (it.homeServerAddress.endsWith(":8766")) newHome ?: home else home
                    else -> phone
                }
            },
            embedder = FakeEmbedder(),
            recovery = recovery,
            now = now,
        )
        captions.cloudRecheckSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 0.0
        return captions
    }

    private fun on(captions: CaptionPipeline, kind: TranscriptionEngineKind) =
        captions.phase == PipelinePhase.Listening && captions.activeEngineKind == kind

    private fun lost(kind: EngineUnavailability.Kind = EngineUnavailability.Kind.NoInternet) = EngineUnavailability(kind, "connection lost")

    private fun failedWith(captions: CaptionPipeline) = (captions.phase as? PipelinePhase.Failed)?.reason?.engineUnavailability?.kind

    @Test
    fun `an installed phone model that fails to load hands over to the home computer, and the cloud comes back later`() = runTest {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit, availability = loadFailure())
        val captions = pipelineOf(cloud, phone, homeAnswering())
        captions.start(settings())
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        cloud.endStream(throwing = lost())
        assertTrue(eventually { on(captions, homeKind) })
        assertTrue(captions.isCoveringForCloud)
        assertEquals(1, phone.prepareCount)
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        assertFalse(captions.isCoveringForCloud)
    }

    @Test
    fun `a phone model that fails to load and a home computer that does not answer stay as before`() = runTest {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit, availability = loadFailure())
        val captions = pipelineOf(cloud, phone, homeAnswering(false))
        captions.start(settings())
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        cloud.endStream(throwing = lost())
        assertTrue(eventually { failedWith(captions) == EngineUnavailability.Kind.ModelLoadFailed })
        assertTrue(captions.activeEngineKind != homeKind)
    }

    @Test
    fun `a phone model that fails to load with no home address stays as before`() = runTest {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit, availability = loadFailure())
        val home = homeAnswering()
        val captions = pipelineOf(cloud, phone, home)
        captions.start(settings(paired = false))
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        cloud.endStream(throwing = lost())
        assertTrue(eventually { failedWith(captions) == EngineUnavailability.Kind.ModelLoadFailed })
        assertEquals(0, home.prepareCount)
    }

    @Test
    fun `the home computer failing while it covers does not start a phone model that is not installed, and the cloud still comes back`() = runTest {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit).also { it.pendingDownload = 800 }
        val home = homeAnswering()
        val retries = AutoRecoveryPolicy(glitchDelays = listOf(0.05, 0.05), downloadDelays = emptyList(), microphoneTailDelays = emptyList())
        val captions = pipelineOf(cloud, phone, home, recovery = retries)
        captions.start(settings())
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        cloud.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "still down")
        cloud.endStream(throwing = lost())
        assertTrue(eventually { on(captions, homeKind) })
        home.endStream(throwing = lost(EngineUnavailability.Kind.HomeServerUnreachable))
        assertTrue(eventually { on(captions, homeKind) && home.prepareCount >= 2 })
        assertEquals(0, phone.prepareCount)
        assertTrue(captions.isCoveringForCloud)
        cloud.availability = EngineAvailability.Available
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        assertEquals(0, phone.prepareCount)
    }

    @Test
    fun `a cloud that is only briefly unavailable and has no phone model is covered by the home computer once the retries run out`() = runTest {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit).also { it.pendingDownload = 800 }
        val captions = pipelineOf(cloud, phone, homeAnswering())
        captions.start(settings())
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        cloud.endStream(throwing = lost(EngineUnavailability.Kind.TemporarilyUnavailable))
        assertTrue(eventually { on(captions, homeKind) })
        assertEquals(0, phone.prepareCount)
    }

    private class Rig(val captions: CaptionPipeline, val audio: FakeAudioCapturer, val http: FakeCloudHTTP, val home: FakeEngine)

    private fun TestScope.hangingRig(homeAnswers: Boolean = true, now: () -> Double = { virtualNow() }, newHome: FakeEngine? = null): Rig {
        val http = FakeCloudHTTP(listOf(FakeCloudHTTP.Answer.Text("hello")))
        val cloud = CloudSpeechEngine(http = http, failedSegmentPauseSeconds = 0.001, approvalSeconds = 0.01, timeSource = testScheduler.timeSource, apiKey = { "sk-test" })
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit).also { it.pendingDownload = 400 }
        val home = homeAnswering(homeAnswers)
        val audio = FakeAudioCapturer()
        return Rig(pipelineOf(cloud, phone, home, audio, now = now, newHome = newHome), audio, http, home)
    }

    private suspend fun Rig.talk(steps: Int, until: () -> Boolean) {
        repeat(steps) {
            if (until()) return
            repeat(21) { audio.push(FloatArray(1_600) { i -> if (i % 2 == 0) 0.2f else -0.2f }) }
            eventually(300.milliseconds) { until() }
        }
    }

    @Test
    fun `no answer at all with only the home computer to cover - it takes over after the first timeout`() = runTest {
        val rig = hangingRig()
        rig.captions.start(settings())
        rig.http.blackHole = true
        rig.talk(6) { rig.captions.isCoveringForCloud }
        assertTrue(eventually { rig.captions.isCoveringForCloud && on(rig.captions, homeKind) })
        assertEquals(1, rig.http.transcriptionRequests.size)
        assertTrue(rig.home.prepareCount >= 1)
    }

    @Test
    fun `without a home address a timeout is retried as before and nothing is handed over`() = runTest {
        val rig = hangingRig()
        rig.captions.start(settings(paired = false))
        rig.http.blackHole = true
        rig.talk(3) { rig.http.transcriptionRequests.size >= 2 }
        assertTrue(rig.http.transcriptionRequests.size >= 2)
        assertFalse(rig.captions.isCoveringForCloud)
        assertEquals(TranscriptionEngineKind.Cloud, rig.captions.activeEngineKind)
    }

    @Test
    fun `a pause and resume while the home computer covers goes on with the home computer, not a phone model that is not installed`() = runTest {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit).also { it.pendingDownload = 800 }
        val captions = pipelineOf(cloud, phone, homeAnswering())
        captions.start(settings())
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        cloud.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "still down")
        cloud.endStream(throwing = lost())
        assertTrue(eventually { on(captions, homeKind) })
        captions.pause()
        captions.resume(settings())
        assertTrue(eventually { on(captions, homeKind) })
        assertEquals(0, phone.prepareCount)
        cloud.availability = EngineAvailability.Available
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
    }

    @Test
    fun `the home computer failing after covering for a phone model that would not load leaves the cloud as the one to go back to`() = runTest {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit, availability = loadFailure())
        val home = homeAnswering()
        val captions = pipelineOf(cloud, phone, home)
        captions.start(settings())
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        cloud.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "still down")
        cloud.endStream(throwing = lost())
        assertTrue(eventually { on(captions, homeKind) })
        home.endStream(throwing = lost(EngineUnavailability.Kind.HomeServerUnreachable))
        assertTrue(eventually { failedWith(captions) == EngineUnavailability.Kind.ModelLoadFailed && phone.prepareCount >= 2 })
        cloud.availability = EngineAvailability.Available
        assertTrue(eventually { on(captions, TranscriptionEngineKind.Cloud) })
        assertFalse(captions.isCoveringForCloud)
    }

    @Test
    fun `a saved home address that does not answer - a timeout is retried by the cloud as before, with no failure and no hand-over`() = runTest {
        val rig = hangingRig(homeAnswers = false)
        rig.captions.start(settings())
        rig.http.blackHole = true
        rig.talk(15) { rig.http.transcriptionRequests.size >= 2 }
        assertTrue(rig.http.transcriptionRequests.size >= 2)
        assertFalse(rig.captions.isCoveringForCloud)
        assertEquals(PipelinePhase.Listening, rig.captions.phase)
        assertEquals(TranscriptionEngineKind.Cloud, rig.captions.activeEngineKind)
    }

    /**
     * Counts the times a home computer is asked while captions have not
     * failed: the cloud timeout's check. The stop after four failed
     * requests asks again on its own, once captions have failed.
     */
    private class Asked(engine: FakeEngine, captions: CaptionPipeline, afterFirst: () -> Unit = {}) {
        var whileListening = 0
            private set

        init {
            engine.duringPrepare = {
                if (captions.phase !is PipelinePhase.Failed) {
                    whileListening += 1
                    if (whileListening == 1) afterFirst()
                }
            }
        }
    }

    @Test
    fun `an unreachable home computer is asked once for a minute, however often the cloud times out`() = runTest {
        val rig = hangingRig(homeAnswers = false, now = { 1_000.0 })
        val asked = Asked(rig.home, rig.captions)
        rig.captions.start(settings())
        rig.http.blackHole = true
        rig.talk(15) { rig.http.transcriptionRequests.size >= 3 }
        assertTrue(rig.http.transcriptionRequests.size >= 3)
        assertEquals(1, asked.whileListening)
    }

    @Test
    fun `after a minute the unreachable home computer is asked again`() = runTest {
        var clock = 1_000.0
        val rig = hangingRig(homeAnswers = false, now = { clock })
        // The cloud answers again as soon as the first check is made, so no
        // run of failed requests can stop captions while the minute passes.
        val asked = Asked(rig.home, rig.captions) { rig.http.blackHole = false }
        rig.captions.start(settings())
        rig.http.blackHole = true
        rig.talk(15) { asked.whileListening >= 1 }
        assertEquals(1, asked.whileListening)
        val answered = rig.http.transcriptionRequests.size
        rig.talk(15) { rig.http.transcriptionRequests.size > answered }
        clock += 61.0
        rig.http.blackHole = true
        rig.talk(15) { asked.whileListening >= 2 }
        assertEquals(2, asked.whileListening)
    }

    @Test
    fun `a changed home address, applied the way the app does while captions run, is asked at once`() = runTest {
        val newHome = homeAnswering(false)
        val rig = hangingRig(homeAnswers = false, now = { 1_000.0 }, newHome = newHome)
        val asked = Asked(rig.home, rig.captions)
        val askedNew = Asked(newHome, rig.captions)
        rig.captions.start(settings())
        rig.http.blackHole = true
        rig.talk(15) { asked.whileListening >= 1 }
        assertEquals(1, asked.whileListening)
        val other = settings()
        other.homeServerAddress = "wss://192.168.1.30:8766"
        rig.http.blackHole = false
        rig.captions.restart(other)
        rig.http.blackHole = true
        rig.talk(15) { askedNew.whileListening >= 1 }
        assertEquals(1, askedNew.whileListening)
        assertEquals(1, asked.whileListening)
    }
}
