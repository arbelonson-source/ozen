package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private val homeKind = TranscriptionEngineKind.HomeServer

private class CoverEngines(phoneInstalled: Boolean, homeAnswers: Boolean = true) {
    val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
    val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit).also { if (!phoneInstalled) it.pendingDownload = 800 }
    val home = FakeEngine(
        kind = homeKind,
        availability = if (homeAnswers) {
            EngineAvailability.Available
        } else {
            EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "no answer")
        },
    )
}

private fun TestScope.pipelineOf(engines: CoverEngines): CaptionPipeline = captionPipeline(
    audio = FakeAudioCapturer(),
    engineFactory = { settings ->
        when (settings.engine) {
            TranscriptionEngineKind.Cloud -> engines.cloud
            homeKind -> engines.home
            else -> engines.phone
        }
    },
    embedder = FakeEmbedder(),
    recovery = AutoRecoveryPolicy.disabled(),
)

private fun settingsOf(paired: Boolean = true): AppSettings {
    val settings = AppSettings.default
    settings.engine = TranscriptionEngineKind.Cloud
    if (paired) settings.homeServerAddress = "wss://192.168.1.20:8765"
    return settings
}

private fun noInternet() = EngineUnavailability(EngineUnavailability.Kind.NoInternet, "connection lost")

private fun listeningOn(captions: CaptionPipeline, kind: TranscriptionEngineKind) =
    captions.phase == PipelinePhase.Listening && captions.activeEngineKind == kind

class CloudCoverHomeComputerTest {
    @Test
    fun `no internet and no phone model - the paired home computer carries on`() = runTest {
        val engines = CoverEngines(phoneInstalled = false)
        val captions = pipelineOf(engines)
        captions.start(settingsOf())
        assertTrue(eventually { listeningOn(captions, TranscriptionEngineKind.Cloud) })
        engines.cloud.endStream(throwing = noInternet())
        assertTrue(eventually { listeningOn(captions, homeKind) })
        assertTrue(captions.isCoveringForCloud)
        assertEquals(EngineUnavailability.Kind.NoInternet, captions.coverReason)
        assertEquals(0, engines.phone.prepareCount)
    }

    @Test
    fun `the cloud answering again takes captions back from the home computer`() = runTest {
        val engines = CoverEngines(phoneInstalled = false)
        val captions = pipelineOf(engines)
        captions.cloudRecheckSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 0.0
        captions.start(settingsOf())
        assertTrue(eventually { listeningOn(captions, TranscriptionEngineKind.Cloud) })
        engines.cloud.endStream(throwing = noInternet())
        assertTrue(eventually { captions.activeEngineKind == homeKind })
        assertTrue(eventually { listeningOn(captions, TranscriptionEngineKind.Cloud) })
        assertFalse(captions.isCoveringForCloud)
        assertNull(captions.coverReason)
    }

    @Test
    fun `without a paired home computer nothing covers, as before`() = runTest {
        val engines = CoverEngines(phoneInstalled = false)
        val captions = pipelineOf(engines)
        captions.start(settingsOf(paired = false))
        assertTrue(eventually { listeningOn(captions, TranscriptionEngineKind.Cloud) })
        engines.cloud.endStream(throwing = noInternet())
        assertTrue(eventually { (captions.phase as? PipelinePhase.Failed)?.reason?.engineUnavailability?.kind == EngineUnavailability.Kind.NoInternet })
        assertFalse(captions.isCoveringForCloud)
        assertEquals(0, engines.home.prepareCount)
    }

    @Test
    fun `a home computer that does not answer is not started`() = runTest {
        val engines = CoverEngines(phoneInstalled = false, homeAnswers = false)
        val captions = pipelineOf(engines)
        captions.start(settingsOf())
        assertTrue(eventually { listeningOn(captions, TranscriptionEngineKind.Cloud) })
        engines.cloud.endStream(throwing = noInternet())
        assertTrue(eventually { (captions.phase as? PipelinePhase.Failed)?.reason?.engineUnavailability?.kind == EngineUnavailability.Kind.NoInternet })
        assertFalse(captions.isCoveringForCloud)
        assertTrue(captions.activeEngineKind != homeKind)
    }

    @Test
    fun `a phone model that is installed still covers first`() = runTest {
        val engines = CoverEngines(phoneInstalled = true)
        val captions = pipelineOf(engines)
        captions.start(settingsOf())
        assertTrue(eventually { listeningOn(captions, TranscriptionEngineKind.Cloud) })
        engines.cloud.endStream(throwing = noInternet())
        assertTrue(eventually { listeningOn(captions, TranscriptionEngineKind.WhisperKit) })
        assertTrue(captions.isCoveringForCloud)
        assertEquals(0, engines.home.prepareCount)
    }

    @Test
    fun `a missing key is for a person to fix - the home computer does not quietly cover it`() = runTest {
        val engines = CoverEngines(phoneInstalled = false)
        engines.cloud.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.CloudKeyNeeded, "no key")
        val captions = pipelineOf(engines)
        captions.start(settingsOf())
        assertTrue(eventually { (captions.phase as? PipelinePhase.Failed)?.reason?.engineUnavailability?.kind == EngineUnavailability.Kind.CloudKeyNeeded })
        assertFalse(captions.isCoveringForCloud)
        assertEquals(0, engines.home.prepareCount)
    }
}
