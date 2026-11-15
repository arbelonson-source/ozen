package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ReplayHalfLineTest {
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

    private suspend fun Rig.dropAndReplay() {
        cloud.endStream(throwing = EngineUnavailability(EngineUnavailability.Kind.NoInternet, "gone"))
        assertTrue(eventually { captions.isCoveringForCloud && captions.phase == PipelinePhase.Listening })
        audio.push(FloatArray(1_600) { 0.5f })
        assertTrue(eventually { phone.samplesSeen.any { it == 0.5f } })
    }

    private fun TestScope.token(text: String, final: Boolean) =
        TranscriptToken(utteranceID = UUID.randomUUID(), text = text, isFinal = final, timestamp = virtualNow())

    @Test
    fun `a half line inside the replayed audio is replaced on screen and in what History saves`() = runTest {
        val rig = rig()
        rig.captions.start(cloudSettings())
        rig.said(3.0, 0.1f)
        rig.cloud.emit(token("line A is done", true))
        assertTrue(eventually { rig.captions.segments.size == 1 })
        rig.said(6.0, 0.2f)
        rig.cloud.emit(token("line B starts", false))
        assertTrue(eventually { rig.captions.segments.size == 2 })
        rig.dropAndReplay()
        assertTrue(rig.captions.segments.last().text.startsWith("line B starts"))

        rig.phone.emit(token("line B starts and ends", true))
        assertTrue(eventually { rig.captions.segments.last().text == "line B starts and ends" })
        assertEquals(listOf("line A is done", "line B starts and ends"), rig.captions.segments.map { it.text })
    }

    @Test
    fun `a half line that began before the replayed audio keeps its words`() = runTest {
        val rig = rig()
        rig.captions.start(cloudSettings())
        rig.said(5.0, 0.2f)
        rig.cloud.emit(token("early words", false))
        assertTrue(eventually { rig.captions.segments.size == 1 })
        rig.said(35.0, 0.2f)
        rig.dropAndReplay()

        rig.phone.emit(token("later words", true))
        assertTrue(eventually { rig.captions.segments.size == 2 })
        assertTrue(rig.captions.segments.first().text.startsWith("early words"))
        assertEquals("later words", rig.captions.segments.last().text)
    }

    @Test
    fun `with nothing to replay the half line stays and the new line follows it`() = runTest {
        val rig = rig()
        rig.captions.start(cloudSettings())
        rig.said(5.0, 0f)
        rig.cloud.emit(token("half line", false))
        assertTrue(eventually { rig.captions.segments.size == 1 })
        rig.dropAndReplay()

        rig.phone.emit(token("new line", true))
        assertTrue(eventually { rig.captions.segments.size == 2 })
        assertTrue(rig.captions.segments.first().text.startsWith("half line"))
        assertEquals("new line", rig.captions.segments.last().text)
    }

    @Test
    fun `a replayed line the silence guard refuses leaves the half line in place`() = runTest {
        val rig = rig()
        rig.captions.start(cloudSettings())
        rig.said(5.0, 0.2f)
        rig.cloud.emit(token("half line", false))
        assertTrue(eventually { rig.captions.segments.size == 1 })
        rig.dropAndReplay()

        rig.phone.emit(token("תודה. תודה. תודה.", true))
        delay(300)
        assertEquals(1, rig.captions.segments.size)
        assertTrue(rig.captions.segments.first().text.startsWith("half line"))
    }
}
