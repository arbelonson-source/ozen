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

private val cloudKind = TranscriptionEngineKind.Cloud
private val phoneKind = TranscriptionEngineKind.WhisperKit

private fun TestScope.coverPipeline(
    cloud: FakeEngine,
    phone: FakeEngine,
    audio: FakeAudioCapturer = FakeAudioCapturer(),
    recovery: AutoRecoveryPolicy = AutoRecoveryPolicy.disabled(),
): CaptionPipeline = captionPipeline(
    audio = audio,
    engineFactory = { settings -> if (settings.engine == cloudKind) cloud else phone },
    embedder = FakeEmbedder(),
    recovery = recovery,
)

private fun TestScope.coverPipeline(cloud: FakeEngine, phone: FakeEngine, retryAfter: List<Double>): CaptionPipeline =
    coverPipeline(cloud, phone, recovery = AutoRecoveryPolicy(glitchDelays = retryAfter, downloadDelays = emptyList()))

private fun cloudSettings(): AppSettings {
    val settings = AppSettings.default
    settings.engine = cloudKind
    return settings
}

private fun lostConnection() = EngineUnavailability(EngineUnavailability.Kind.NoInternet, "connection lost")

private fun failureOf(kind: EngineUnavailability.Kind) =
    PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", EngineUnavailability(kind, ""))

private fun TestScope.spokenToken(text: String) =
    TranscriptToken(utteranceID = UUID.randomUUID(), text = text, isFinal = true, timestamp = virtualNow())

private class Causes {
    val seen = ArrayList<StoppedCaptionsNotice.Cause?>()
}

/**
 * What the app's "captions stopped" check sees each time the phase
 * changes, looked at after the pipeline has finished reacting, as the
 * app does.
 */
private fun TestScope.watchCauses(captions: CaptionPipeline): Causes {
    val causes = Causes()
    val scope = this
    captions.onPhaseChange = {
        scope.launch {
            causes.seen.add(
                StoppedCaptionsNotice.cause(
                    phase = captions.phase,
                    retryScheduled = captions.isRecoveringByItself,
                    systemInterrupted = false,
                    callEndedDuringInterruption = false,
                ),
            )
        }
    }
    return causes
}

class CloudCoverTest {
    @Test
    fun `out of credit, no key or no internet - the downloaded phone model carries on`() = runTest {
        for (kind in listOf(EngineUnavailability.Kind.CloudOutOfCredit, EngineUnavailability.Kind.CloudKeyNeeded, EngineUnavailability.Kind.NoInternet)) {
            val cloud = FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(kind, "test"))
            val phone = FakeEngine(kind = phoneKind)
            val captions = coverPipeline(cloud, phone)
            captions.start(cloudSettings())
            assertTrue(eventually { captions.phase == PipelinePhase.Listening }, "$kind")
            assertTrue(captions.isCoveringForCloud)
            assertEquals(phoneKind, captions.activeEngineKind)
            assertEquals(phoneKind, captions.activeSettings?.engine)
        }
    }

    @Test
    fun `a phone model that would have to download first is not started behind her back`() = runTest {
        val cloud = FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.CloudOutOfCredit, "test"))
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 800
        val captions = coverPipeline(cloud, phone)
        captions.start(cloudSettings())
        assertTrue(eventually { phone.pendingDownloadChecks > 0 })
        assertEquals(EngineUnavailability.Kind.CloudOutOfCredit, captions.phase.failure?.engineUnavailability?.kind)
        assertFalse(captions.isCoveringForCloud)
        assertEquals(0, phone.prepareCount)
    }

    @Test
    fun `while the phone's model gets ready to take over, captions are coming back, not stopped`() = runTest {
        val cloud = FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "test"))
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone)
        val causes = watchCauses(captions)
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.isCoveringForCloud })
        assertTrue(eventually { causes.seen.size >= 3 })
        assertTrue(causes.seen.all { it == null })
    }

    @Test
    fun `a cover that can't start still says captions stopped`() = runTest {
        val cloud = FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.CloudOutOfCredit, "test"))
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 800
        val captions = coverPipeline(cloud, phone)
        val causes = watchCauses(captions)
        captions.start(cloudSettings())
        assertTrue(eventually { causes.seen.lastOrNull() is StoppedCaptionsNotice.Cause.Failed })
        assertFalse(captions.isRecoveringByItself)
    }

    @Test
    fun `a passing cloud problem is retried as before, not covered`() = runTest {
        val cloud = FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.TemporarilyUnavailable, "test"))
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone, retryAfter = listOf(60.0))
        captions.start(cloudSettings())
        assertEquals(EngineUnavailability.Kind.TemporarilyUnavailable, captions.phase.failure?.engineUnavailability?.kind)
        assertNotNull(captions.scheduledRetry)
        delay(200.milliseconds)
        assertEquals(0, phone.prepareCount)
        assertFalse(captions.isCoveringForCloud)
    }

    @Test
    fun `a cloud still busy after every retry is covered, not left stopped`() = runTest {
        val cloud = FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.TemporarilyUnavailable, "test"))
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone, retryAfter = listOf(0.01, 0.01))
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.isCoveringForCloud })
        assertEquals(phoneKind, captions.activeEngineKind)
        assertEquals(EngineUnavailability.Kind.TemporarilyUnavailable, captions.coverReason)
        assertTrue(cloud.prepareCount >= 3)
    }

    @Test
    fun `a cloud still busy after every retry is covered without saying captions stopped`() = runTest {
        val cloud = FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.TemporarilyUnavailable, "test"))
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone, retryAfter = listOf(0.01, 0.01))
        val causes = watchCauses(captions)
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.isCoveringForCloud })
        assertTrue(eventually { causes.seen.size >= 5 })
        assertTrue(causes.seen.all { it == null })
    }

    @Test
    fun `starting again with her own settings ends the cover`() = runTest {
        val engines = listOf(
            FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "test")),
            FakeEngine(kind = cloudKind),
        )
        val phone = FakeEngine(kind = phoneKind)
        val cloudBuilt = BuiltEngines()
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                if (settings.engine != cloudKind) {
                    phone
                } else {
                    val engine = engines[minOf(cloudBuilt.count, 1)]
                    cloudBuilt.add(engine)
                    engine
                }
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        captions.start(cloudSettings())
        assertTrue(eventually { captions.isCoveringForCloud && captions.phase == PipelinePhase.Listening })
        captions.restart(cloudSettings())
        assertEquals(PipelinePhase.Listening, captions.phase)
        assertFalse(captions.isCoveringForCloud)
        assertEquals(cloudKind, captions.activeEngineKind)
    }

    @Test
    fun `pausing and resuming with her cloud settings keeps the phone's model on, without building it again`() = runTest {
        val cloud = FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.CloudOutOfCredit, "test"))
        val phone = FakeEngine(kind = phoneKind)
        val built = BuiltEngines()
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                val engine = if (settings.engine == cloudKind) cloud else phone
                built.add(engine)
                engine
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        captions.start(cloudSettings())
        assertTrue(eventually { captions.isCoveringForCloud && captions.phase == PipelinePhase.Listening })
        val builtBefore = built.count
        captions.pause()
        captions.resume(cloudSettings())
        assertEquals(PipelinePhase.Listening, captions.phase)
        assertTrue(captions.isCoveringForCloud)
        assertEquals(phoneKind, captions.activeEngineKind)
        assertEquals(builtBefore, built.count)
    }

    @Test
    fun `a home computer address changed while paused is tried on resume, instead of the phone's model covering for the old one`() = runTest {
        val old = FakeEngine(kind = TranscriptionEngineKind.HomeServer, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "test"))
        val fixed = FakeEngine(kind = TranscriptionEngineKind.HomeServer)
        val phone = FakeEngine(kind = phoneKind)
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                if (settings.engine != TranscriptionEngineKind.HomeServer) {
                    phone
                } else if (settings.homeServerAddress == "10.0.0.9") {
                    fixed
                } else {
                    old
                }
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.HomeServer
        settings.homeServerAddress = "10.0.0.5"
        captions.start(settings)
        assertTrue(eventually { captions.isCoveringForCloud && captions.phase == PipelinePhase.Listening })
        captions.pause()
        settings.homeServerAddress = "10.0.0.9"
        captions.settingsChangedWhilePaused()
        captions.resume(settings)
        assertEquals(PipelinePhase.Listening, captions.phase)
        assertFalse(captions.isCoveringForCloud)
        assertEquals(TranscriptionEngineKind.HomeServer, captions.activeEngineKind)
        assertEquals(1, fixed.prepareCount)
    }

    @Test
    fun `an engine already on the phone is never swapped`() {
        assertNull(CloudCover.phoneSettings(AppSettings.default, failureOf(EngineUnavailability.Kind.NoInternet)))
    }

    @Test
    fun `once the internet returns, cloud captions switch back by themselves`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone)
        captions.cloudRecheckSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 0.0
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { captions.activeEngineKind == phoneKind })
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        assertFalse(captions.isCoveringForCloud)
        assertNull(captions.coverReason)
    }

    @Test
    fun `with talk that never goes quiet, only answers in a row bring the cloud back - every other one keeps the phone's model on`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone)
        captions.cloudRecheckSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 1000.0
        captions.switchBackAfterAnsweredChecks = 2
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        var switchesBack = 0
        captions.onEvent = { event ->
            if (event.description == "the cloud answers again, switching back to it") switchesBack += 1
        }
        val flaky = TestSwitch(true)
        cloud.duringPrepare = {
            if (flaky.isOn) {
                cloud.availability = if (cloud.availability == EngineAvailability.Available) {
                    EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "flaky")
                } else {
                    EngineAvailability.Available
                }
            }
        }
        val before = cloud.prepareCount
        var spoken = 0
        while (cloud.prepareCount - before < 8 && spoken < 200) {
            phone.emit(spokenToken("הטלוויזיה מדברת $spoken"))
            spoken += 1
            delay(20.milliseconds)
        }
        assertTrue(cloud.prepareCount - before >= 8)
        assertEquals(0, switchesBack)
        assertEquals(phoneKind, captions.activeEngineKind)

        flaky.isOn = false
        cloud.availability = EngineAvailability.Available
        while (captions.activeEngineKind == phoneKind && spoken < 400) {
            phone.emit(spokenToken("הטלוויזיה מדברת $spoken"))
            spoken += 1
            delay(20.milliseconds)
        }
        assertEquals(cloudKind, captions.activeEngineKind)
        assertEquals(1, switchesBack)
    }

    @Test
    fun `with talk that never goes quiet, the cloud comes back after exactly the answered checks asked for`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone)
        captions.cloudRecheckSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 1000.0
        captions.switchBackAfterAnsweredChecks = 3
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "offline")
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        // As in the home computer's twin: checks go unanswered until one is
        // held, the count starts just before it, and a line is said first.
        val gate = PrepareGate()
        cloud.prepareGate = gate
        assertTrue(eventually { cloud.heldPrepares == 1 })
        val before = cloud.prepareCount - 1
        cloud.availability = EngineAvailability.Available
        phone.emit(spokenToken("הטלוויזיה מדברת"))
        assertTrue(eventually { captions.segments.lastOrNull()?.text == "הטלוויזיה מדברת" })
        gate.open()
        var spoken = 0
        while (captions.activeEngineKind == phoneKind && spoken < 60) {
            phone.emit(spokenToken("הטלוויזיה מדברת $spoken"))
            spoken += 1
            delay(20.milliseconds)
        }
        assertEquals(cloudKind, captions.activeEngineKind)
        // The engine kind changes as the switch starts, before it asks.
        assertTrue(eventually { captions.phase == PipelinePhase.Listening })
        // Exactly the answered checks asked for, then the switch itself.
        assertEquals(captions.switchBackAfterAnsweredChecks + 1, cloud.prepareCount - before)
        captions.stop()
    }

    @Test
    fun `an alert word or a name added while the phone's model covers is still there after switching back`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone)
        captions.cloudRecheckSeconds = 1.0
        captions.homeServerSwitchBackQuietSeconds = 0.0
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })

        captions.setKeywordAlerts(listOf(KeywordAlert(phrase = "Ruti", isEnabled = true)))
        captions.setVocabulary(listOf("Avi"))

        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        assertEquals(listOf("Ruti"), captions.activeSettings?.keywordAlerts?.map { it.phrase })
        assertEquals(listOf("Avi"), captions.activeSettings?.vocabulary)
        captions.stop()
    }

    @Test
    fun `a sound choice, the speaker sensitivity or the microphone changed while the phone's model covers stays after switching back`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val audio = FakeAudioCapturer()
        audio.availableInputs = audio.availableInputs + AudioInputDescriptor(uid = "usb", portName = "USB Microphone", portType = AudioPortType.Usb)
        val captions = coverPipeline(cloud, phone, audio = audio)
        captions.cloudRecheckSeconds = 1.0
        captions.homeServerSwitchBackQuietSeconds = 0.0
        val settings = cloudSettings()
        settings.preferredInputUID = "builtin"
        captions.start(settings)
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })

        val sounds = settings.soundAlerts.copy(mutedIdentifiers = settings.soundAlerts.mutedIdentifiers + "door_bell")
        captions.setSoundAlertPreferences(sounds)
        captions.setSpeakerSimilarityThreshold(0.9f)
        assertTrue(captions.selectInput("usb"))

        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        assertEquals(sounds, captions.soundPolicy.preferences)
        assertEquals(0.9f, captions.speakerSimilarityThreshold)
        assertEquals("usb", audio.selectedInputUID)
        captions.stop()
    }

    @Test
    fun `when the phone's model fails while it covers, the cloud answering again is switched to instead of captions staying stopped`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone)
        captions.cloudRecheckSeconds = 0.2
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "offline")
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })

        phone.endStream(throwing = EngineUnavailability(EngineUnavailability.Kind.ModelLoadFailed, "out of memory"))
        assertTrue(eventually { captions.phase is PipelinePhase.Failed })
        cloud.availability = EngineAvailability.Available
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        assertFalse(captions.isCoveringForCloud)
        captions.stop()
    }

    @Test
    fun `a failed cover isn't switched back to the cloud during a phone call, which holds the microphone, but is right after it`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone)
        captions.cloudRecheckSeconds = 0.1
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.NoInternet, "offline")
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })

        captions.systemInterruptionChanged(true)
        phone.endStream(throwing = EngineUnavailability(EngineUnavailability.Kind.ModelLoadFailed, "out of memory"))
        assertTrue(eventually { captions.phase is PipelinePhase.Failed })
        cloud.availability = EngineAvailability.Available
        delay(600.milliseconds)
        assertNotNull(captions.phase.failure)

        captions.systemInterruptionChanged(false)
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        captions.stop()
    }

    @Test
    fun `a retry after the covering model itself fails keeps covering, and still switches back once the cloud answers`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone)
        captions.cloudRecheckSeconds = 1.0
        captions.homeServerSwitchBackQuietSeconds = 0.0
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })

        phone.endStream(throwing = EngineUnavailability(EngineUnavailability.Kind.TemporarilyUnavailable, "microphone stalled"))
        assertTrue(eventually { captions.phase is PipelinePhase.Failed })
        captions.retry()

        assertTrue(captions.isCoveringForCloud)
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        captions.stop()
    }

    @Test
    fun `a connection that keeps dropping right after switching back is tried less and less often, a drop after a good stretch starts over`() = runTest {
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = coverPipeline(cloud, phone)
        captions.cloudRecheckSeconds = 0.1
        captions.homeServerSwitchBackQuietSeconds = 0.0
        captions.start(cloudSettings())
        // Doubles up to 16 times the minute, then stays there.
        for (wait in listOf(0.1, 0.2, 0.4, 0.8, 1.6, 1.6)) {
            assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
            cloud.endStream(throwing = lostConnection())
            assertTrue(eventually { captions.activeEngineKind == phoneKind && captions.currentCloudRecheckSeconds == wait })
        }

        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        captions.cloudFlapWindowSeconds = 0.0
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { captions.activeEngineKind == phoneKind && captions.currentCloudRecheckSeconds == 0.1 })
        captions.stop()
    }

    @Test
    fun `a key or credit problem is left for a person, never asked again on its own`() = runTest {
        for (kind in listOf(EngineUnavailability.Kind.CloudKeyNeeded, EngineUnavailability.Kind.CloudOutOfCredit)) {
            val cloud = FakeEngine(kind = cloudKind, availability = EngineAvailability.unavailable(kind, "test"))
            val phone = FakeEngine(kind = phoneKind)
            val captions = coverPipeline(cloud, phone)
            captions.cloudRecheckSeconds = 0.05
            captions.homeServerSwitchBackQuietSeconds = 0.0
            captions.start(cloudSettings())
            assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.isCoveringForCloud }, "$kind")
            val before = cloud.prepareCount
            delay(300.milliseconds)
            assertEquals(before, cloud.prepareCount, "$kind")
            assertEquals(phoneKind, captions.activeEngineKind, "$kind")
        }
    }

    @Test
    fun `what is said while the phone's model loads to take over is kept for it, not lost`() = runTest {
        val audio = FakeAudioCapturer()
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val gate = PrepareGate()
        phone.prepareGate = gate
        val captions = coverPipeline(cloud, phone, audio = audio)
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { phone.prepareCount == 1 })
        repeat(20) { audio.push(FloatArray(256) { 0.1f }) }
        gate.open()
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertTrue(eventually { phone.chunksSeen >= 20 })
    }

    @Test
    fun `a microphone the phone gives up on while its model loads to take over stops captions then, not after the load`() = runTest {
        val audio = FakeAudioCapturer()
        val cloud = FakeEngine(kind = cloudKind)
        val phone = FakeEngine(kind = phoneKind)
        val gate = PrepareGate()
        phone.prepareGate = gate
        val captions = coverPipeline(cloud, phone, audio = audio)
        captions.start(cloudSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == cloudKind })
        cloud.endStream(throwing = lostConnection())
        assertTrue(eventually { phone.prepareCount == 1 })
        assertTrue(captions.phase is PipelinePhase.PreparingEngine, "expected the phone's model to be loading, got ${captions.phase}")

        audio.onCaptureLost?.invoke()
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, captions.phase.failure?.kind)
        assertEquals(1, captions.stats.audioStalls)
        gate.open()
        delay(100.milliseconds)
        assertEquals(PipelineFailure.Kind.AudioSessionFailed, captions.phase.failure?.kind)
    }
}
