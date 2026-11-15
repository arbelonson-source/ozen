package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

/**
 * A cloud connection that never answers hands over to the phone's model
 * after one timeout (CloudHangingConnectionTests on the iPhone).
 */
class CloudHangingConnectionTest {
    private class Rig(val captions: CaptionPipeline, val audio: FakeAudioCapturer, val phone: FakeEngine, val http: FakeCloudHTTP)

    private fun TestScope.rig(http: FakeCloudHTTP): Rig {
        val cloud: TranscriptionEngine = CloudSpeechEngine(http = http, failedSegmentPauseSeconds = 0.001, approvalSeconds = 0.01, timeSource = testScheduler.timeSource, apiKey = { "sk-test" })
        val phone = FakeEngine(kind = TranscriptionEngineKind.WhisperKit)
        val audio = FakeAudioCapturer()
        val captions = captionPipeline(
            audio = audio,
            engineFactory = { if (it.engine == TranscriptionEngineKind.Cloud) cloud else phone },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        captions.cloudRecheckSeconds = 0.05
        captions.homeServerSwitchBackQuietSeconds = 0.0
        return Rig(captions, audio, phone, http)
    }

    private fun cloudSettings(): AppSettings {
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.Cloud
        return settings
    }

    private fun Rig.speak(seconds: Double, level: Float) {
        repeat((seconds * 10).toInt()) { audio.push(FloatArray(1_600) { i -> if (i % 2 == 0) level else -level }) }
    }

    private suspend fun Rig.talk(steps: Int, until: () -> Boolean) {
        repeat(steps) {
            if (until()) return
            speak(2.1, 0.2f)
            eventually(300.milliseconds) { until() }
        }
    }

    @Test
    fun `no answer at all - the phone's model takes over after the first timeout, with the speech replayed, and the cloud takes captions back once it answers`() = runTest {
        val rig = rig(FakeCloudHTTP(listOf(FakeCloudHTTP.Answer.Text("hello"))))
        rig.captions.start(cloudSettings())
        rig.http.blackHole = true
        val startedAt = testScheduler.currentTime
        rig.talk(6) { rig.captions.isCoveringForCloud }
        assertTrue(eventually { rig.captions.isCoveringForCloud && rig.captions.phase == PipelinePhase.Listening && rig.captions.activeEngineKind == TranscriptionEngineKind.WhisperKit })
        assertEquals(1, rig.http.transcriptionRequests.size)
        assertTrue(testScheduler.currentTime - startedAt < 20_000, "handed over in well under the old two and a half minutes")

        rig.audio.push(FloatArray(1_600) { 0.5f })
        assertTrue(eventually { rig.phone.samplesSeen.any { it == 0.5f } })
        val heard = rig.phone.samplesSeen
        val live = heard.indexOfFirst { it == 0.5f }
        assertTrue(heard.take(live).count { abs(it) == 0.2f } >= 32_000)

        delay(300)
        assertTrue(rig.captions.isCoveringForCloud)
        rig.http.blackHole = false
        assertTrue(eventually { !rig.captions.isCoveringForCloud && rig.captions.activeEngineKind == TranscriptionEngineKind.Cloud })
        assertEquals(PipelinePhase.Listening, rig.captions.phase)
    }

    @Test
    fun `a refusal with an answer in it still follows the old path - every round is tried before the phone takes over`() = runTest {
        val rig = rig(FakeCloudHTTP(listOf(FakeCloudHTTP.Answer.Status(503, "busy"))))
        rig.captions.start(cloudSettings())
        rig.talk(12) { rig.captions.isCoveringForCloud }
        assertTrue(eventually { rig.captions.isCoveringForCloud })
        assertTrue(rig.http.transcriptionRequests.size >= CloudSpeechEngine.FAILURES_BEFORE_STOPPING)
    }

    @Test
    fun `without the phone's model installed a timeout is retried as before and nothing is handed over`() = runTest {
        val rig = rig(FakeCloudHTTP(listOf(FakeCloudHTTP.Answer.Text("hello"))))
        rig.phone.pendingDownload = 400
        rig.captions.start(cloudSettings())
        rig.http.blackHole = true
        rig.talk(3) { rig.http.transcriptionRequests.size >= 2 }
        assertTrue(rig.http.transcriptionRequests.size >= 2)
        assertFalse(rig.captions.isCoveringForCloud)
        assertEquals(TranscriptionEngineKind.Cloud, rig.captions.activeEngineKind)
    }
}
