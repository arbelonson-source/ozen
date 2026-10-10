package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PipelineCaptionsChangedTest {
    @Test
    fun `each new or changed line, and clearing, calls the hook`() = runTest {
        val engine = FakeEngine()
        // As the tokens' times, or the stale-commit safety net can finish
        // the line between them on a slow machine: one call too many.
        val pipeline = captionPipeline(audio = FakeAudioCapturer(), engineFactory = { engine }, embedder = FakeEmbedder(), now = { 1_001.0 })
        var calls = 0
        pipeline.onCaptionsChanged = { calls += 1 }
        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()
        engine.emit(TranscriptToken(utteranceID = id, text = "hello", isFinal = false, timestamp = 1_000.0))
        assertTrue(eventually { calls == 1 })
        engine.emit(TranscriptToken(utteranceID = id, text = "hello there", isFinal = true, timestamp = 1_001.0))
        assertTrue(eventually { calls == 2 })
        pipeline.clearTranscript()
        assertEquals(3, calls)
    }
}
