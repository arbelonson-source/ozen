package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private val homeKind = TranscriptionEngineKind.HomeServer
private val cloudKind = TranscriptionEngineKind.Cloud
private val phoneKind = TranscriptionEngineKind.WhisperKit

private class Built(val kinds: ArrayList<TranscriptionEngineKind> = ArrayList(), val variants: ArrayList<String> = ArrayList(), val files: ArrayList<String?> = ArrayList())

private fun TestScope.pipelineFor(engines: Map<TranscriptionEngineKind, FakeEngine>, built: Built): CaptionPipeline = captionPipeline(
    audio = FakeAudioCapturer(),
    engineFactory = { settings ->
        built.kinds.add(settings.engine)
        built.variants.add(settings.whisperModelVariant)
        built.files.add(settings.phoneModelFile)
        engines.getValue(settings.engine)
    },
    embedder = FakeEmbedder(),
    recovery = AutoRecoveryPolicy.disabled(),
)

private fun settingsFor(kind: TranscriptionEngineKind): AppSettings {
    val settings = AppSettings.default
    settings.engine = kind
    settings.homeServerAddress = "10.0.0.5"
    return settings
}

private fun lost() = EngineUnavailability(EngineUnavailability.Kind.HomeServerUnreachable, "asleep")

class PhoneModelChangeTest {
    @Test
    fun `a model chosen while the home computer captions does not restart them and a later cover loads it`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val built = Built()
        val captions = pipelineFor(mapOf(homeKind to server, phoneKind to phone), built)
        captions.start(settingsFor(homeKind))
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        val restarts = captions.stats.engineRestarts
        captions.phoneModelChanged("small", "small.bin") { settingsFor(homeKind) }
        assertEquals(PipelinePhase.Listening, captions.phase)
        assertEquals(homeKind, captions.activeEngineKind)
        assertEquals(restarts, captions.stats.engineRestarts)
        assertEquals(listOf(homeKind), built.kinds)
        server.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "asleep")
        server.endStream(throwing = lost())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertEquals("small", built.variants.last())
        assertEquals("small.bin", built.files.last())
    }

    @Test
    fun `a model chosen while the cloud captions does not restart them and a later cover loads it`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val built = Built()
        val captions = pipelineFor(mapOf(cloudKind to cloud, phoneKind to phone), built)
        captions.start(settingsFor(cloudKind))
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        val restarts = captions.stats.engineRestarts
        captions.phoneModelChanged("small", "small.bin") { settingsFor(cloudKind) }
        assertEquals(PipelinePhase.Listening, captions.phase)
        assertEquals(restarts, captions.stats.engineRestarts)
        assertEquals(listOf(cloudKind), built.kinds)
        assertEquals("small", captions.activeSettings?.whisperModelVariant)
        assertEquals("small.bin", captions.activeSettings?.phoneModelFile)
    }

    @Test
    fun `a model chosen while the phone captions restarts with it`() = runTest {
        val phone = FakeEngine(kind = phoneKind)
        val built = Built()
        val captions = pipelineFor(mapOf(phoneKind to phone), built)
        captions.start(settingsFor(phoneKind))
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        val chosen = settingsFor(phoneKind)
        chosen.whisperModelVariant = "small"
        chosen.phoneModelFile = "small.bin"
        captions.phoneModelChanged("small", "small.bin") { chosen }
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && built.kinds.size == 2 })
        assertEquals("small", built.variants.last())
        assertEquals("small.bin", built.files.last())
    }

    @Test
    fun `a model chosen while the phone already covers waits for the next load`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val built = Built()
        val captions = pipelineFor(mapOf(homeKind to server, phoneKind to phone), built)
        captions.start(settingsFor(homeKind))
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        server.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "asleep")
        server.endStream(throwing = lost())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        val loads = built.kinds.size
        captions.phoneModelChanged("small", "small.bin") { settingsFor(homeKind) }
        assertEquals(PipelinePhase.Listening, captions.phase)
        assertEquals(phoneKind, captions.activeEngineKind)
        assertEquals(loads, built.kinds.size)
    }
}
