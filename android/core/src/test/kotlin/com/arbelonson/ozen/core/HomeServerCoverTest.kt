package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private val homeKind = TranscriptionEngineKind.HomeServer
private val phoneKind = TranscriptionEngineKind.WhisperKit

private fun serverSettings(): AppSettings {
    val settings = AppSettings.default
    settings.engine = homeKind
    settings.homeServerAddress = "10.0.0.5"
    return settings
}

private fun unreachable(detail: String = "connection lost") =
    EngineUnavailability(EngineUnavailability.Kind.HomeServerUnreachable, detail)

private fun TestScope.spoken(text: String) =
    TranscriptToken(utteranceID = UUID.randomUUID(), text = text, isFinal = true, timestamp = virtualNow())

private fun TestScope.serverPipeline(
    server: FakeEngine,
    phone: FakeEngine,
    audio: FakeAudioCapturer = FakeAudioCapturer(),
    soundDetector: FakeSoundDetector? = null,
    recovery: AutoRecoveryPolicy = AutoRecoveryPolicy.disabled(),
): CaptionPipeline = captionPipeline(
    audio = audio,
    engineFactory = { settings -> if (settings.engine == homeKind) server else phone },
    embedder = FakeEmbedder(),
    soundDetector = soundDetector,
    recovery = recovery,
)

private fun unreachableServer() =
    FakeEngine(kind = homeKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "asleep"))

private fun oneQuickRetry() = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList())

private fun FakeAudioCapturer.startCaptures(): Int = calls.count { it == "startCapture" }

private fun quiet() = FloatArray(800) { 0.0002f }

private fun voice() = FloatArray(800) { (sin(it.toDouble() * 0.3) * 0.2).toFloat() }

private data class Waiting(
    val captions: CaptionPipeline,
    val server: FakeEngine,
    val phone: FakeEngine,
    val audio: FakeAudioCapturer,
    val detector: FakeSoundDetector,
)

/** Captions waiting for a computer that is off, with sound alerts as set. */
private suspend fun TestScope.waitingCaptions(soundAlerts: Boolean = true): Waiting {
    val server = unreachableServer()
    val phone = FakeEngine(kind = phoneKind)
    phone.pendingDownload = 819
    val audio = FakeAudioCapturer()
    val detector = FakeSoundDetector()
    val captions = serverPipeline(server, phone, audio, detector, oneQuickRetry())
    captions.homeServerRecheckSeconds = 1000.0
    captions.homeServerWaitSeconds = 0.05
    val settings = serverSettings()
    settings.soundAlerts = settings.soundAlerts.copy(isEnabled = soundAlerts)
    captions.start(settings)
    assertTrue(eventually { server.prepareCount >= 3 && captions.scheduledRetry == null })
    return Waiting(captions, server, phone, audio, detector)
}

class HomeServerCoverTest {
    @Test
    fun `an unreachable server or a refused code is covered by the downloaded phone model`() = runTest {
        for (kind in listOf(EngineUnavailability.Kind.HomeServerUnreachable, EngineUnavailability.Kind.HomeServerRejected)) {
            val server = FakeEngine(kind = homeKind, availability = EngineAvailability.unavailable(kind, "test"))
            val phone = FakeEngine(kind = phoneKind)
            val captions = serverPipeline(server, phone)
            captions.start(serverSettings())
            assertTrue(eventually { captions.phase == PipelinePhase.Listening }, "$kind")
            assertTrue(captions.isCoveringForCloud)
            assertEquals(phoneKind, captions.activeEngineKind)
            assertEquals(kind, captions.coverReason, "the screen must be able to say which of the two it was")
        }
    }

    @Test
    fun `the server going away mid-conversation hands over to the phone instead of stopping`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone)
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertTrue(captions.isCoveringForCloud)
    }

    @Test
    fun `the computer's answer is remembered for less than the wait between switch-back checks, so every check really asks it`() = runTest {
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { FakeEngine(kind = homeKind) },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        assertTrue(HomeServerEngine.DEFAULT_APPROVAL_SECONDS < captions.homeServerRecheckSeconds)
    }

    @Test
    fun `once the unreachable computer answers again, captions go back to it by themselves`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone)
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 0.0
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.activeEngineKind == phoneKind })
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        assertFalse(captions.isCoveringForCloud)
        assertNull(captions.coverReason)
    }

    @Test
    fun `with no backup on the phone, captions start again by themselves once the computer answers, not only after a tap`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val captions = serverPipeline(server, phone, recovery = oneQuickRetry())
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { server.prepareCount >= 2 && captions.scheduledRetry == null })
        delay(100.milliseconds)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, captions.phase.failure?.engineUnavailability?.kind)
        assertEquals(0, phone.prepareCount, "no surprise download of the backup")

        server.availability = EngineAvailability.Available
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        assertFalse(captions.isCoveringForCloud)
    }

    @Test
    fun `stopped captions ask the computer sooner than covered ones look to switch back - nothing is captioned meanwhile`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val captions = serverPipeline(server, phone, recovery = oneQuickRetry())
        assertTrue(captions.homeServerWaitSeconds * 4 <= captions.homeServerRecheckSeconds)
        captions.homeServerRecheckSeconds = 1000.0
        captions.homeServerWaitSeconds = 0.05
        val notes = ArrayList<String>()
        captions.onEvent = { event -> notes.add(event.description) }
        captions.start(serverSettings())
        assertTrue(eventually { server.prepareCount >= 2 && captions.scheduledRetry == null })
        val waitStarted = server.prepareCount
        assertTrue(eventually { server.prepareCount >= waitStarted + 3 })
        assertEquals(
            listOf("waiting for the home computer, asking it every 0.05 s"),
            notes.filter { it.startsWith("waiting for the home computer") },
        )

        server.availability = EngineAvailability.Available
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
    }

    @Test
    fun `while captions wait for the computer a smoke alarm still alerts, and captions take the microphone back once it answers`() = runTest {
        val (captions, server, _, audio, detector) = waitingCaptions()
        assertTrue(eventually { captions.stats.soundDetectionRunning })
        // What keeps the app's battery warnings on while captions are down.
        assertTrue(captions.isListeningForSoundsOnly)
        audio.push(FloatArray(1_024) { 0.1f })
        assertTrue(eventually { detector.chunksSeen == 1 })
        detector.push(SoundObservation("smoke_detector", 0.9, 100.0))
        assertTrue(eventually { captions.soundAlerts.size == 1 })

        server.availability = EngineAvailability.Available
        assertTrue(eventually { captions.phase == PipelinePhase.Listening })
        assertFalse(captions.isListeningForSoundsOnly)
        detector.push(SoundObservation("door_bell", 0.9, 200.0))
        assertTrue(eventually { captions.soundAlerts.size == 2 })

        captions.stop()
        assertFalse(captions.stats.soundDetectionRunning)
        assertEquals("stopCapture", audio.calls.last())
    }

    @Test
    fun `a smoke alarm still alerts while captions wait for their next try`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val detector = FakeSoundDetector()
        val captions = serverPipeline(
            server,
            phone,
            soundDetector = detector,
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(1000.0), downloadDelays = emptyList()),
        )
        captions.start(serverSettings())
        assertTrue(eventually { captions.scheduledRetry != null && captions.stats.soundDetectionRunning })
        detector.push(SoundObservation("smoke_detector", 0.9, 100.0))
        assertTrue(eventually { captions.soundAlerts.size == 1 })
        captions.stop()
    }

    @Test
    fun `with sound alerts off, the microphone stays off while captions wait for the computer`() = runTest {
        val (captions, _, _, audio, _) = waitingCaptions(soundAlerts = false)
        delay(100.milliseconds)
        assertFalse(captions.stats.soundDetectionRunning)
        assertFalse(audio.calls.contains("startCapture"))
    }

    @Test
    fun `a phone call during the wait takes the microphone from sound alerts, and they listen again after it`() = runTest {
        val (captions, _, _, audio, _) = waitingCaptions()
        assertTrue(eventually { captions.stats.soundDetectionRunning })
        captions.systemInterruptionChanged(true)
        assertFalse(captions.stats.soundDetectionRunning)
        assertEquals("stopCapture", audio.calls.last())
        captions.systemInterruptionChanged(false)
        assertTrue(eventually { captions.stats.soundDetectionRunning })
    }

    @Test
    fun `a voice sample recorded during the wait has the microphone to itself, and sound alerts listen again after it`() = runTest {
        val (captions, _, _, audio, _) = waitingCaptions()
        assertTrue(eventually { captions.stats.soundDetectionRunning })
        val startsBefore = audio.startCaptures()
        val recording = async { captions.captureEnrollmentSamples(0.5) }
        assertTrue(eventually { audio.startCaptures() == startsBefore + 1 })
        assertFalse(captions.stats.soundDetectionRunning)
        audio.push(FloatArray(8_000) { 0.1f })
        assertEquals(8_000, recording.await().size)
        assertNotNull(captions.phase.failure)
        assertTrue(captions.stats.soundDetectionRunning)
    }

    @Test
    fun `a backup that takes over during the wait gets the microphone from sound alerts, never with two captures open`() = runTest {
        val (captions, _, phone, audio, detector) = waitingCaptions()
        assertTrue(eventually { captions.stats.soundDetectionRunning })
        phone.pendingDownload = null
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        var open = false
        for (call in audio.calls.filter { it == "startCapture" || it == "stopCapture" }) {
            assertFalse(open && call == "startCapture", "${audio.calls}")
            open = call == "startCapture"
        }
        detector.push(SoundObservation("door_bell", 0.9, 100.0))
        assertTrue(eventually { captions.soundAlerts.size == 1 })
    }

    @Test
    fun `a backup that finishes downloading while captions wait for the computer takes over, as its Settings row promises`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val captions = serverPipeline(server, phone, recovery = oneQuickRetry())
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { server.prepareCount >= 2 && captions.scheduledRetry == null })
        delay(100.milliseconds)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, captions.phase.failure?.engineUnavailability?.kind)

        phone.pendingDownload = null
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertTrue(captions.isCoveringForCloud)
    }

    @Test
    fun `a backup model picked while captions wait for the computer is the one that takes over once it is downloaded`() = runTest {
        val server = unreachableServer()
        val firstPick = FakeEngine(kind = phoneKind)
        firstPick.pendingDownload = 819
        val secondPick = FakeEngine(kind = phoneKind)
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                if (settings.engine == homeKind) {
                    server
                } else if (settings.whisperModelVariant == "second-pick") {
                    secondPick
                } else {
                    firstPick
                }
            },
            embedder = FakeEmbedder(),
            recovery = oneQuickRetry(),
        )
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { server.prepareCount >= 2 && captions.scheduledRetry == null })
        delay(100.milliseconds)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, captions.phase.failure?.engineUnavailability?.kind)

        captions.setWhisperModelVariant("second-pick")
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertEquals(1, secondPick.prepareCount)
        assertEquals(0, firstPick.prepareCount)
    }

    @Test
    fun `names added while captions wait for the computer reach the backup that takes over`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val captions = serverPipeline(server, phone, recovery = oneQuickRetry())
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { server.prepareCount >= 2 && captions.scheduledRetry == null })
        delay(100.milliseconds)

        captions.setVocabulary(listOf("Dvora"))
        phone.pendingDownload = null
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertEquals(true, phone.vocabularySeen.lastOrNull()?.contains("Dvora"))
    }

    @Test
    fun `a name added while the wait is asking the computer reaches the backup that takes over on that same pass`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val captions = serverPipeline(server, phone, recovery = oneQuickRetry())
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { server.prepareCount >= 2 && captions.scheduledRetry == null })
        val gate = PrepareGate()
        server.prepareGate = gate
        assertTrue(eventually { server.heldPrepares > 0 })

        captions.setVocabulary(listOf("Dvora"))
        phone.pendingDownload = null
        gate.open()
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertEquals(true, phone.vocabularySeen.lastOrNull()?.contains("Dvora"))
    }

    @Test
    fun `a microphone picked while the backup's download is checked is the one the backup starts with`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val captions = serverPipeline(server, phone, recovery = oneQuickRetry())
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { server.prepareCount >= 2 && captions.scheduledRetry == null })

        var picked = false
        phone.duringPendingDownloadCheck = {
            if (!picked) {
                picked = true
                captions.selectInput("lapel")
                phone.pendingDownload = null
            }
        }
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertEquals("lapel", captions.activeSettings?.preferredInputUID)
    }

    @Test
    fun `a backup that turns out not ready at the last check leaves the wait running, and it takes over once ready`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val captions = serverPipeline(server, phone, recovery = oneQuickRetry())
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { server.prepareCount >= 2 && captions.scheduledRetry == null })

        var checks = 0
        phone.duringPendingDownloadCheck = {
            checks += 1
            phone.pendingDownload = if (checks == 2) 819 else null
        }
        assertTrue(eventually { checks >= 2 })
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
    }

    @Test
    fun `a computer that answers while a voice sample records starts captions after the recording, without cutting it short`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val audio = FakeAudioCapturer()
        val captions = serverPipeline(server, phone, audio, recovery = oneQuickRetry())
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { server.prepareCount >= 2 && captions.scheduledRetry == null })
        delay(100.milliseconds)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, captions.phase.failure?.engineUnavailability?.kind)

        val recording = async { captions.captureEnrollmentSamples(1.0) }
        assertTrue(eventually { captions.isRecordingVoice })
        server.availability = EngineAvailability.Available
        delay(300.milliseconds)
        audio.push(FloatArray(16_000) { 0.1f })
        val sample = recording.await()
        assertEquals(16_000, sample.size)
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
    }

    @Test
    fun `an automatic retry that comes due while a voice sample records waits for it, instead of cutting it short and giving up`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val audio = FakeAudioCapturer()
        val captions = serverPipeline(
            server,
            phone,
            audio,
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(0.2), downloadDelays = emptyList()),
        )
        captions.homeServerRecheckSeconds = 60.0
        captions.homeServerWaitSeconds = 60.0
        captions.start(serverSettings())
        assertTrue(eventually { captions.scheduledRetry != null })

        val recording = async { captions.captureEnrollmentSamples(1.0) }
        assertTrue(eventually { captions.isRecordingVoice })
        server.availability = EngineAvailability.Available
        delay(400.milliseconds)
        audio.push(FloatArray(16_000) { 0.1f })
        val sample = recording.await()
        assertEquals(16_000, sample.size)
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
    }

    @Test
    fun `while the phone covers, looking for the computer again keeps the phone's loaded model, so a pause and resume doesn't load it again`() = runTest {
        val server = unreachableServer()
        val phone = FakeEngine(kind = phoneKind)
        var phonesBuilt = 0
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                if (settings.engine == homeKind) {
                    server
                } else {
                    phonesBuilt += 1
                    phone
                }
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertTrue(eventually { server.prepareCount >= 3 })
        captions.pause()
        captions.resume(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertEquals(1, phonesBuilt)
    }

    @Test
    fun `a phone model chosen while the home computer captions is the one a later cover loads`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val variants = ArrayList<String>()
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                if (settings.engine == homeKind) {
                    server
                } else {
                    variants.add(settings.whisperModelVariant)
                    phone
                }
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        captions.setWhisperModelVariant("small")
        server.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "asleep")
        server.endStream(throwing = unreachable("asleep"))
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertEquals(listOf("small"), variants)
    }

    @Test
    fun `a refused code is left for a person to fix, not asked again and again`() = runTest {
        val server = FakeEngine(kind = homeKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerRejected, "wrong code"))
        val phone = FakeEngine(kind = phoneKind)
        phone.pendingDownload = 819
        val captions = serverPipeline(server, phone, recovery = oneQuickRetry())
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase.failure != null && captions.scheduledRetry == null })
        delay(150.milliseconds)
        val asked = server.prepareCount
        delay(300.milliseconds)
        assertEquals(asked, server.prepareCount)
    }

    @Test
    fun `the switch back waits while someone is mid-sentence`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone)
        captions.homeServerRecheckSeconds = 0.3
        captions.homeServerWaitSeconds = 0.3
        captions.homeServerSwitchBackQuietSeconds = 0.0
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        val line = UUID.randomUUID()
        phone.emit(TranscriptToken(utteranceID = line, text = "סבתא, את", isFinal = false, timestamp = virtualNow()))
        assertTrue(eventually { captions.segments.lastOrNull()?.text == "סבתא, את" })
        delay(700.milliseconds)
        assertEquals(phoneKind, captions.activeEngineKind)
        phone.emit(TranscriptToken(utteranceID = line, text = "סבתא, את באה?", isFinal = true, timestamp = virtualNow()))
        assertTrue(eventually { captions.activeEngineKind == homeKind })
    }

    @Test
    fun `the switch back waits while someone is talking, even before the phone's model has written a word`() = runTest {
        val audio = FakeAudioCapturer()
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone, audio)
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 1.0
        captions.switchBackAfterAnsweredChecks = 1000
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        repeat(5) {
            audio.push(quiet())
            audio.push(voice())
            delay(10.milliseconds)
        }
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        val talkUntil = testScheduler.timeSource.markNow() + 800.milliseconds
        while (talkUntil.hasNotPassedNow()) {
            audio.push(quiet())
            audio.push(voice())
            delay(20.milliseconds)
        }
        assertEquals(phoneKind, captions.activeEngineKind)
        assertTrue(eventually { captions.activeEngineKind == homeKind })
    }

    @Test
    fun `a name added while the computer is asked whether it is back goes with the switch back`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone)
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 0.0
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        val gate = PrepareGate()
        server.prepareGate = gate
        val asked = server.prepareCount
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        assertTrue(eventually { server.prepareCount > asked })

        captions.setVocabulary(listOf("Dvora"))
        server.prepareGate = null
        gate.open()
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        assertEquals(true, server.vocabularySeen.lastOrNull()?.contains("Dvora"))
    }

    @Test
    fun `after enough answered checks, going back still waits for a breath in the talk, and looks for one without waiting for the next check`() = runTest {
        val audio = FakeAudioCapturer()
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone, audio)
        captions.homeServerRecheckSeconds = 2.0
        captions.homeServerWaitSeconds = 2.0
        captions.homeServerSwitchBackQuietSeconds = 1000.0
        captions.switchBackAfterAnsweredChecks = 1
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        repeat(5) {
            audio.push(quiet())
            audio.push(voice())
            delay(10.milliseconds)
        }
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        val asked = server.prepareCount
        while (server.prepareCount == asked) {
            audio.push(quiet())
            audio.push(voice())
            delay(20.milliseconds)
        }
        val firstCheck = testScheduler.timeSource.markNow()
        val talkUntil = firstCheck + 500.milliseconds
        while (talkUntil.hasNotPassedNow()) {
            audio.push(quiet())
            audio.push(voice())
            delay(20.milliseconds)
        }
        assertEquals(phoneKind, captions.activeEngineKind)
        assertTrue(eventually { captions.activeEngineKind == homeKind })
        // The next check is 2 s after the first; the breath came about 0.9 s after it.
        assertTrue(firstCheck.elapsedNow() < 1_700.milliseconds)
    }

    @Test
    fun `talk with no breath through a whole search stays on the phone's model until a later check finds one`() = runTest {
        val audio = FakeAudioCapturer()
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone, audio)
        captions.homeServerRecheckSeconds = 0.2
        captions.homeServerWaitSeconds = 0.2
        captions.homeServerSwitchBackQuietSeconds = 1000.0
        captions.switchBackAfterAnsweredChecks = 1
        captions.switchBackBreathWaitSeconds = 0.3
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        repeat(5) {
            audio.push(quiet())
            audio.push(voice())
            delay(10.milliseconds)
        }
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        val asked = server.prepareCount
        val talkUntil = testScheduler.timeSource.markNow() + 1_500.milliseconds
        while (talkUntil.hasNotPassedNow()) {
            audio.push(quiet())
            audio.push(voice())
            delay(20.milliseconds)
        }
        assertTrue(server.prepareCount - asked >= 2)
        assertEquals(phoneKind, captions.activeEngineKind)
        assertTrue(eventually { captions.activeEngineKind == homeKind })
    }

    @Test
    fun `with talk that never goes quiet, captions still go back after a few answered checks, between two lines`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone)
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 1000.0
        captions.switchBackAfterAnsweredChecks = 3
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        server.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "asleep")
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        // Checks come every 50 ms, so one could land before the count is
        // read: they go unanswered until one is held, and the count starts
        // just before that one, the first to be answered. A line is said
        // first, or with nothing said yet it is already between sentences.
        val gate = PrepareGate()
        server.prepareGate = gate
        assertTrue(eventually { server.heldPrepares == 1 })
        val before = server.prepareCount - 1
        server.availability = EngineAvailability.Available
        phone.emit(spoken("הטלוויזיה מדברת"))
        assertTrue(eventually { captions.segments.lastOrNull()?.text == "הטלוויזיה מדברת" })
        gate.open()
        var count = 0
        while (captions.activeEngineKind == phoneKind && count < 60) {
            phone.emit(spoken("הטלוויזיה מדברת $count"))
            count += 1
            delay(20.milliseconds)
        }
        assertEquals(homeKind, captions.activeEngineKind)
        // The engine kind changes as the switch starts, before it asks the
        // computer; listening again, the switch has asked.
        assertTrue(eventually { captions.phase == PipelinePhase.Listening })
        // Exactly the answered checks asked for, then the switch itself.
        assertEquals(captions.switchBackAfterAnsweredChecks + 1, server.prepareCount - before)
    }

    @Test
    fun `those answered checks must come in a row - a computer that answers every other time keeps the phone's model on`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone)
        captions.homeServerRecheckSeconds = 0.05
        captions.homeServerWaitSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 1000.0
        captions.switchBackAfterAnsweredChecks = 2
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == phoneKind })
        var switchesBack = 0
        captions.onEvent = { event ->
            if (event.description == "the home computer answers again, switching back to it") switchesBack += 1
        }
        val flaky = TestSwitch(true)
        server.duringPrepare = {
            if (flaky.isOn) {
                server.availability = if (server.availability == EngineAvailability.Available) {
                    EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "flaky")
                } else {
                    EngineAvailability.Available
                }
            }
        }
        val before = server.prepareCount
        var count = 0
        while (server.prepareCount - before < 8 && count < 200) {
            phone.emit(spoken("הטלוויזיה מדברת $count"))
            count += 1
            delay(20.milliseconds)
        }
        assertTrue(server.prepareCount - before >= 8)
        assertEquals(0, switchesBack)
        assertEquals(phoneKind, captions.activeEngineKind)

        flaky.isOn = false
        server.availability = EngineAvailability.Available
        while (captions.activeEngineKind == phoneKind && count < 400) {
            phone.emit(spoken("הטלוויזיה מדברת $count"))
            count += 1
            delay(20.milliseconds)
        }
        assertEquals(homeKind, captions.activeEngineKind)
        assertEquals(1, switchesBack)
    }

    @Test
    fun `pausing and resuming with her home-computer settings keeps the phone's model on while the computer is down`() = runTest {
        val server = FakeEngine(kind = homeKind, availability = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "test"))
        val built = BuiltEngines()
        val phone = FakeEngine(kind = phoneKind)
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                val engine = if (settings.engine == homeKind) server else phone
                built.add(engine)
                engine
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        captions.start(serverSettings())
        assertTrue(eventually { captions.isCoveringForCloud && captions.phase == PipelinePhase.Listening })
        val tries = server.prepareCount
        val builtBefore = built.count
        captions.pause()
        captions.resume(serverSettings())
        assertEquals(PipelinePhase.Listening, captions.phase)
        assertTrue(captions.isCoveringForCloud)
        assertEquals(phoneKind, captions.activeEngineKind)
        assertEquals(tries, server.prepareCount)
        assertEquals(builtBefore, built.count)
        captions.stop()
    }

    @Test
    fun `a computer that keeps dropping right after captions go back to it is tried less and less often, a drop after a good stretch starts over`() = runTest {
        val server = FakeEngine(kind = homeKind)
        val phone = FakeEngine(kind = phoneKind)
        val captions = serverPipeline(server, phone)
        // Long enough that the phone's turn outlasts a busy one-core
        // runner's scheduling delay: at 0.02 s the switch back could
        // happen between two polls, and the test missed the phone's turn.
        captions.homeServerRecheckSeconds = 0.1
        captions.homeServerWaitSeconds = 0.1
        captions.homeServerSwitchBackQuietSeconds = 0.0
        captions.start(serverSettings())
        // The wait is set just after the switch to the phone; read it once
        // it has settled. It doubles up to 16 times, then stays there.
        for (wait in listOf(0.1, 0.2, 0.4, 0.8, 1.6, 1.6)) {
            assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
            server.endStream(throwing = unreachable("no reply for 35 s of speech"))
            assertTrue(eventually { captions.activeEngineKind == phoneKind && captions.currentHomeServerRecheckSeconds == wait })
        }

        assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.activeEngineKind == homeKind })
        captions.homeServerFlapWindowSeconds = 0.0
        server.endStream(throwing = unreachable())
        assertTrue(eventually { captions.activeEngineKind == phoneKind && captions.currentHomeServerRecheckSeconds == 0.1 })
        captions.stop()
    }

    @Test
    fun `a computer still out of reach is asked again and again, a refused code is left for a person`() = runTest {
        val cases = listOf(
            EngineUnavailability.Kind.HomeServerUnreachable to true,
            EngineUnavailability.Kind.HomeServerRejected to false,
        )
        for ((kind, asksAgain) in cases) {
            val server = FakeEngine(kind = homeKind, availability = EngineAvailability.unavailable(kind, "test"))
            val phone = FakeEngine(kind = phoneKind)
            val captions = serverPipeline(server, phone)
            captions.homeServerRecheckSeconds = 0.05
            captions.homeServerWaitSeconds = 0.05
            captions.homeServerSwitchBackQuietSeconds = 0.0
            captions.start(serverSettings())
            assertTrue(eventually { captions.phase == PipelinePhase.Listening && captions.isCoveringForCloud })
            val before = server.prepareCount
            delay(400.milliseconds)
            assertEquals(asksAgain, server.prepareCount > before, "$kind")
            assertEquals(phoneKind, captions.activeEngineKind)
            captions.stop()
        }
    }

    @Test
    fun `a new server address builds a new engine instead of reusing the one for the old address`() = runTest {
        val addresses = ArrayList<String>()
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { settings ->
                addresses.add(settings.homeServerAddress)
                FakeEngine(kind = homeKind)
            },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        captions.start(serverSettings())
        assertTrue(eventually { captions.phase == PipelinePhase.Listening })
        val moved = serverSettings()
        moved.homeServerAddress = "10.0.0.9"
        captions.restart(moved)
        assertTrue(eventually { captions.phase == PipelinePhase.Listening })
        assertEquals(listOf("10.0.0.5", "10.0.0.9"), addresses)
    }
}
