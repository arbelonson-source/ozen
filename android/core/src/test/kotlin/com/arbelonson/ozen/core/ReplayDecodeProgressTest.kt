package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ReplayDecodeProgressTest {
    private class Rig(val captions: CaptionPipeline, val audio: FakeAudioCapturer, val cloud: FakeEngine, val phone: FakeEngine)

    private fun TestScope.rig(decoding: Boolean): Rig {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit)
        phone.reportsDecoding = decoding
        val audio = FakeAudioCapturer()
        val captions = captionPipeline(
            audio = audio,
            engineFactory = { if (it.engine == TranscriptionEngineKind.Cloud) cloud else phone },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        return Rig(captions, audio, cloud, phone)
    }

    private fun TestScope.token(text: String) =
        TranscriptToken(utteranceID = UUID.randomUUID(), text = text, isFinal = true, timestamp = virtualNow())

    private suspend fun Rig.push(seconds: Double, level: Float) {
        val received = captions.stats.audioSecondsReceived
        repeat((seconds * 10).toInt()) { audio.push(FloatArray(1_600) { i -> if (i % 2 == 0) level else -level }) }
        assertTrue(eventually { captions.stats.audioSecondsReceived >= received + seconds - 0.01 })
    }

    /** A half line, a cloud drop, a replay, and ten seconds of live audio the model took at once; returns the replay's length. */
    private suspend fun TestScope.halfLineThenDropWithEagerModel(rig: Rig): Int {
        rig.captions.start(AppSettings.default.also { it.engine = TranscriptionEngineKind.Cloud })
        rig.push(5.0, 0.2f)
        rig.cloud.emit(token("half line").copy(isFinal = false))
        assertTrue(eventually { rig.captions.segments.size == 1 })
        rig.cloud.endStream(throwing = EngineUnavailability(EngineUnavailability.Kind.NoInternet, "gone"))
        assertTrue(eventually { rig.captions.isCoveringForCloud && rig.captions.phase == PipelinePhase.Listening })
        rig.audio.push(FloatArray(1_600) { 0.5f })
        assertTrue(eventually { rig.phone.samplesSeen.any { it == 0.5f } })
        val before = rig.phone.samplesSeen.size
        rig.push(10.0, 0.2f)
        assertTrue(eventually { rig.phone.samplesSeen.size >= before + 160_000 })
        return rig.phone.samplesSeen.size - 161_600
    }

    @Test
    fun `a model that took all the audio at once but has decoded little of it still replaces the half line`() = runTest {
        val rig = rig(decoding = true)
        val replayLength = halfLineThenDropWithEagerModel(rig)
        rig.phone.reportDecoded(replayLength + 32_000)
        rig.phone.emit(token("half line and the rest"))
        assertTrue(eventually { rig.captions.segments.last().text == "half line and the rest" })
        assertEquals(listOf("half line and the rest"), rig.captions.segments.map { it.text })
    }

    @Test
    fun `a replay with no words keeps the half line once the model has decoded past the grace`() = runTest {
        val rig = rig(decoding = true)
        val replayLength = halfLineThenDropWithEagerModel(rig)
        rig.phone.reportDecoded(replayLength + 32_000)
        rig.phone.reportDecoded(replayLength + 160_000)
        rig.phone.emit(token("live words"))
        assertTrue(eventually { rig.captions.segments.size == 2 })
        assertEquals(listOf("half line", "live words"), rig.captions.segments.map { it.text.removeSuffix("…") })
    }

    @Test
    fun `a report past the grace handled before the pass's words still lets those words replace the half line`() = runTest {
        val rig = rig(decoding = true)
        val replayLength = halfLineThenDropWithEagerModel(rig)
        rig.phone.reportDecoded(replayLength + 160_000, tokens = 1)
        assertTrue(eventually { true })
        rig.phone.emit(token("half line and the rest"))
        assertTrue(eventually { rig.captions.segments.last().text == "half line and the rest" })
        assertEquals(listOf("half line and the rest"), rig.captions.segments.map { it.text })
        rig.phone.emit(token("live words"))
        assertTrue(eventually { rig.captions.segments.size == 2 })
        assertEquals(listOf("half line and the rest", "live words"), rig.captions.segments.map { it.text })
    }

    @Test
    fun `a model that says nothing about its decoding is counted by the audio it took as before`() = runTest {
        val rig = rig(decoding = false)
        val replayLength = halfLineThenDropWithEagerModel(rig)
        rig.phone.reportDecoded(replayLength + 32_000)
        rig.phone.emit(token("live words"))
        assertTrue(eventually { rig.captions.segments.size == 2 })
        assertEquals(listOf("half line", "live words"), rig.captions.segments.map { it.text.removeSuffix("…") })
    }
}
