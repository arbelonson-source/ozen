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
 * A cloud engine that fails hands the phone's model the speech it never
 * finished, as the home computer does (CloudReplayTests on the iPhone).
 */
class CloudReplayTest {
    private class Rig(val captions: CaptionPipeline, val audio: FakeAudioCapturer, val cloud: FakeEngine, val phone: FakeEngine)

    private fun TestScope.rig(): Rig {
        val cloud = FakeEngine(kind = TranscriptionEngineKind.Cloud)
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit)
        val audio = FakeAudioCapturer()
        val captions = captionPipeline(
            audio = audio,
            engineFactory = { if (it.engine == TranscriptionEngineKind.Cloud) cloud else phone },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        return Rig(captions, audio, cloud, phone)
    }

    private fun cloudSettings(): AppSettings {
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.Cloud
        return settings
    }

    private suspend fun Rig.said(seconds: Double, level: Float) {
        val received = captions.stats.audioSecondsReceived
        repeat((seconds * 10).toInt()) { audio.push(FloatArray(1_600) { i -> if (i % 2 == 0) level else -level }) }
        assertTrue(eventually { captions.stats.audioSecondsReceived >= received + seconds - 0.01 })
    }

    private suspend fun Rig.fail(kind: EngineUnavailability.Kind) {
        cloud.endStream(throwing = EngineUnavailability(kind, "gone"))
        assertTrue(eventually { captions.isCoveringForCloud && captions.phase == PipelinePhase.Listening })
    }

    private fun TestScope.line(text: String) =
        TranscriptToken(utteranceID = UUID.randomUUID(), text = text, isFinal = true, timestamp = virtualNow())

    @Test
    fun `a cloud that finished line A and fails in the middle of line B - the phone hears B and not A again`() = runTest {
        for (kind in listOf(EngineUnavailability.Kind.NoInternet, EngineUnavailability.Kind.CloudKeyNeeded, EngineUnavailability.Kind.CloudOutOfCredit)) {
            val rig = rig()
            rig.captions.start(cloudSettings())
            assertEquals(PipelinePhase.Listening, rig.captions.phase)
            rig.said(3.0, 0.1f)
            rig.cloud.emit(line("line A is done"))
            assertTrue(eventually { rig.captions.segments.size == 1 })
            rig.said(6.0, 0.2f)
            rig.fail(kind)
            rig.audio.push(FloatArray(1_600) { 0.5f })
            assertTrue(eventually { rig.phone.samplesSeen.any { it == 0.5f } }, "$kind")

            val heard = rig.phone.samplesSeen
            val live = heard.indexOfFirst { it == 0.5f }
            assertEquals(96_000, heard.take(live).count { abs(it) == 0.2f }, "$kind")
            assertTrue(heard.take(live).count { abs(it) == 0.1f } <= 32_000, "$kind")
            rig.phone.emit(line("line B is done"))
            assertTrue(eventually { rig.captions.segments.size == 2 }, "$kind")
            assertEquals(listOf("line A is done", "line B is done"), rig.captions.segments.map { it.text }, "$kind")
        }
    }

    @Test
    fun `a failure with nothing finished replays at most the replay window`() = runTest {
        val rig = rig()
        rig.captions.start(cloudSettings())
        rig.said(40.0, 0.2f)
        rig.fail(EngineUnavailability.Kind.NoInternet)
        rig.audio.push(FloatArray(1_600) { 0.5f })
        assertTrue(eventually { rig.phone.samplesSeen.size >= 1_600 + 320_000 })
        delay(100)

        assertEquals(320_000 + 1_600, rig.phone.samplesSeen.size)
    }
}
