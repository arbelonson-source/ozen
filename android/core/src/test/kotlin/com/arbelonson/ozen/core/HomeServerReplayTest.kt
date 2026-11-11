package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The phone notices a home computer that vanished without a clean close
 * only 8-35 s later; what was said meanwhile is heard again by the phone's
 * own model, as on the iPhone (CaptionPipelineHomeLossTests).
 */
class HomeServerReplayTest {
    private class Rig(val captions: CaptionPipeline, val audio: FakeAudioCapturer, val home: FakeEngine, val phone: FakeEngine)

    private fun TestScope.rig(): Rig {
        val home = FakeEngine(kind = TranscriptionEngineKind.HomeServer)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit)
        val audio = FakeAudioCapturer()
        val captions = captionPipeline(
            audio = audio,
            engineFactory = { if (it.engine == TranscriptionEngineKind.HomeServer) home else phone },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        return Rig(captions, audio, home, phone)
    }

    private fun homeSettings(): AppSettings {
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.HomeServer
        settings.homeServerAddress = "10.0.0.5"
        return settings
    }

    private fun speech(seconds: Double, level: Float = 0.2f): List<FloatArray> =
        List((seconds * 10).toInt()) { FloatArray(1_600) { i -> if (i % 2 == 0) level else -level } }

    private fun TestScope.line(text: String, id: UUID = UUID.randomUUID(), isFinal: Boolean = true) =
        TranscriptToken(utteranceID = id, text = text, isFinal = isFinal, timestamp = virtualNow())

    private suspend fun Rig.lose() {
        home.endStream(throwing = EngineUnavailability(EngineUnavailability.Kind.HomeServerUnreachable, "gone"))
        assertTrue(eventually { captions.isCoveringForCloud && captions.phase == PipelinePhase.Listening })
    }

    private suspend fun TestScope.listening(lastLine: String = "hello there"): Rig {
        val rig = rig()
        rig.captions.start(homeSettings())
        assertEquals(PipelinePhase.Listening, rig.captions.phase)
        rig.audio.push(FloatArray(1_600))
        rig.home.emit(line(lastLine))
        assertTrue(eventually { rig.captions.segments.size == 1 })
        return rig
    }

    private suspend fun Rig.said(seconds: Double) {
        speech(seconds).forEach(audio::push)
        val received = captions.stats.audioSecondsReceived
        assertTrue(eventually { captions.stats.audioSecondsReceived >= received + seconds - 0.01 })
    }

    private suspend fun TestScope.takenOverAfter(lastLine: String): Rig {
        val rig = listening(lastLine)
        rig.said(5.0)
        rig.lose()
        assertTrue(eventually { rig.phone.samplesSeen.size >= 80_000 })
        return rig
    }

    private fun Rig.lines() = captions.segments.map { it.text }

    @Test
    fun `speech said after the home computer's last line, before the phone took over, is captioned by the phone first`() = runTest {
        val rig = listening()
        rig.said(10.0)
        rig.lose()
        rig.audio.push(FloatArray(1_600) { 0.5f })
        assertTrue(eventually { rig.phone.samplesSeen.any { it == 0.5f } })

        val heard = rig.phone.samplesSeen
        val live = heard.indexOfFirst { it == 0.5f }
        assertEquals(160_000, heard.take(live).count { abs(it) == 0.2f })
        assertTrue(heard.drop(live).all { it == 0.5f })
    }

    @Test
    fun `a stretch with no speech in it is not replayed`() = runTest {
        val rig = listening()
        repeat(100) { rig.audio.push(FloatArray(1_600)) }
        assertTrue(eventually { rig.captions.stats.audioSecondsReceived >= 10.05 })
        rig.lose()
        rig.audio.push(FloatArray(1_600) { 0.5f })
        assertTrue(eventually { rig.phone.samplesSeen.size >= 1_600 })
        delay(100)

        assertEquals(1_600, rig.phone.samplesSeen.size)
    }

    @Test
    fun `no more than about twenty seconds are replayed`() = runTest {
        val rig = listening()
        rig.said(40.0)
        rig.lose()
        rig.audio.push(FloatArray(1_600) { 0.5f })
        assertTrue(eventually { rig.phone.samplesSeen.size >= 1_600 + 320_000 })
        delay(100)

        assertEquals(320_000 + 1_600, rig.phone.samplesSeen.size)
    }

    @Test
    fun `a replayed line that repeats the home computer's last line is dropped`() = runTest {
        val rig = takenOverAfter("see you at noon tomorrow")
        rig.phone.emit(line("at noon tomorrow"))
        rig.phone.emit(line("bring the keys"))
        assertTrue(eventually { rig.captions.segments.size == 2 })

        assertEquals(listOf("see you at noon tomorrow", "bring the keys"), rig.lines())
    }

    @Test
    fun `a replayed partial that grows past the overlap keeps the new words`() = runTest {
        val rig = takenOverAfter("see you at noon tomorrow")
        val id = UUID.randomUUID()
        rig.phone.emit(line("tomorrow", id, isFinal = false))
        rig.phone.emit(line("tomorrow bring the keys", id, isFinal = false))
        rig.phone.emit(line("tomorrow bring the keys", id))
        assertTrue(eventually { rig.captions.segments.size == 2 && rig.captions.segments.last().isCommitted })

        assertEquals(listOf("see you at noon tomorrow", "bring the keys"), rig.lines())
    }

    @Test
    fun `one phone line of the overlap plus new speech shows the overlap once`() = runTest {
        val rig = takenOverAfter("see you at noon tomorrow")
        rig.phone.emit(line("at noon tomorrow bring the keys"))
        assertTrue(eventually { rig.captions.segments.size == 2 })

        assertEquals(listOf("see you at noon tomorrow", "bring the keys"), rig.lines())
    }

    @Test
    fun `a new line equal to the last word of the previous line is not lost`() = runTest {
        val rig = takenOverAfter("see you at noon tomorrow")
        rig.phone.emit(line("tomorrow"))
        assertTrue(eventually { rig.captions.segments.size == 2 })

        assertEquals(listOf("see you at noon tomorrow", "tomorrow"), rig.lines())
    }

    @Test
    fun `a new line equal to the first words of the previous line is not lost`() = runTest {
        val rig = takenOverAfter("see you at noon tomorrow")
        rig.phone.emit(line("see you"))
        assertTrue(eventually { rig.captions.segments.size == 2 })

        assertEquals(listOf("see you at noon tomorrow", "see you"), rig.lines())
    }

    @Test
    fun `a new line that starts with the last word of the previous line keeps that word`() = runTest {
        val rig = takenOverAfter("see you at noon tomorrow")
        rig.phone.emit(line("tomorrow we go"))
        assertTrue(eventually { rig.captions.segments.size == 2 })

        assertEquals(listOf("see you at noon tomorrow", "tomorrow we go"), rig.lines())
    }

    @Test
    fun `a final that comes long after its speech does not move the replay start forward`() = runTest {
        val rig = rig()
        rig.captions.start(homeSettings())
        rig.audio.push(FloatArray(1_600))
        assertTrue(eventually { rig.captions.stats.audioSecondsReceived >= 0.05 })
        val id = UUID.randomUUID()
        rig.home.emit(line("hello", id, isFinal = false))
        assertTrue(eventually { rig.captions.stats.tokensReceived >= 1 })
        rig.said(8.0)
        rig.home.emit(line("hello there", id))
        assertTrue(eventually { rig.captions.segments.size == 1 && rig.captions.segments[0].isCommitted })
        rig.lose()
        rig.audio.push(FloatArray(1_600) { 0.5f })
        assertTrue(eventually { rig.phone.samplesSeen.any { it == 0.5f } })

        val heard = rig.phone.samplesSeen
        val live = heard.indexOfFirst { it == 0.5f }
        assertEquals(128_000, heard.take(live).count { abs(it) == 0.2f })
    }

    @Test
    fun `stopping while the phone is taking over replays nothing`() = runTest {
        val rig = listening()
        rig.said(10.0)
        val gate = PrepareGate()
        rig.phone.prepareGate = gate
        rig.home.endStream(throwing = EngineUnavailability(EngineUnavailability.Kind.HomeServerUnreachable, "gone"))
        assertTrue(eventually { rig.phone.heldPrepares == 1 })
        rig.captions.stop()
        gate.open()
        delay(200)

        assertTrue(rig.phone.samplesSeen.isEmpty())
        assertEquals(PipelinePhase.Idle, rig.captions.phase)
    }
}
