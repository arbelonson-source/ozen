package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private val cloudKind = TranscriptionEngineKind.Cloud
private val phoneKind = TranscriptionEngineKind.WhisperKit

private fun cloudDown(kind: EngineUnavailability.Kind) =
    FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(kind, "test"))

private fun TestScope.cloudPipeline(
    cloud: FakeEngine,
    detector: FakeSoundDetector? = null,
): CaptionPipeline {
    val phone = FakeEngine(kind = phoneKind)
    phone.modelOnPhone = false
    val captions = captionPipeline(
        audio = FakeAudioCapturer(),
        engineFactory = { settings -> if (settings.engine == cloudKind) cloud else phone },
        embedder = FakeEmbedder(),
        soundDetector = detector,
        recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList()),
    )
    captions.cloudWaitSeconds = 0.05
    return captions
}

private fun cloudSettings(): AppSettings {
    val settings = AppSettings.default
    settings.engine = cloudKind
    return settings
}

class CloudWaitTest {
    @Test
    fun `with no phone model, captions start again by themselves once the cloud answers`() = runTest {
        val cloud = cloudDown(EngineUnavailability.Kind.TemporarilyUnavailable)
        val captions = cloudPipeline(cloud)
        val notes = ArrayList<String>()
        captions.onEvent = { event -> notes.add(event.description) }
        captions.start(cloudSettings())
        assertTrue(eventually { cloud.prepareCount >= 4 && captions.scheduledRetry == null })
        assertFalse(captions.isCoveringForCloud)
        assertEquals(
            listOf("waiting for the cloud, asking it every 0.05 s"),
            notes.filter { it.startsWith("waiting for the cloud") },
        )
        cloud.availability = EngineAvailability.Available
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
    }

    @Test
    fun `a key problem is not asked again on a timer`() = runTest {
        val cloud = cloudDown(EngineUnavailability.Kind.CloudKeyNeeded)
        val captions = cloudPipeline(cloud)
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase is PipelinePhase.Failed })
        val asked = cloud.prepareCount
        delay(300.milliseconds)
        cloud.availability = EngineAvailability.Available
        delay(300.milliseconds)
        assertEquals(asked, cloud.prepareCount)
        assertTrue(captions.phase is PipelinePhase.Failed)
    }

    @Test
    fun `stopping during the wait means nothing restarts when the cloud answers`() = runTest {
        val cloud = cloudDown(EngineUnavailability.Kind.TemporarilyUnavailable)
        val captions = cloudPipeline(cloud)
        captions.start(cloudSettings())
        assertTrue(eventually { cloud.prepareCount >= 4 && captions.scheduledRetry == null })
        captions.stop()
        val asked = cloud.prepareCount
        cloud.availability = EngineAvailability.Available
        delay(300.milliseconds)
        assertEquals(asked, cloud.prepareCount)
        assertFalse(captions.phase.isListening)
    }

    @Test
    fun `a smoke alarm still alerts while captions wait for the cloud`() = runTest {
        val cloud = cloudDown(EngineUnavailability.Kind.TemporarilyUnavailable)
        val detector = FakeSoundDetector()
        val captions = cloudPipeline(cloud, detector)
        captions.start(cloudSettings())
        assertTrue(eventually { cloud.prepareCount >= 4 && captions.scheduledRetry == null })
        assertTrue(eventually { captions.isListeningForSoundsOnly && captions.stats.soundDetectionRunning })
        detector.push(SoundObservation("smoke_detector", 0.9, 100.0))
        assertTrue(eventually { captions.soundAlerts.size == 1 })
        captions.stop()
    }
}
