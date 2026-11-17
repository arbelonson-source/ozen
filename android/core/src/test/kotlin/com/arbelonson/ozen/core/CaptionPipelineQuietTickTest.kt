package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val quietWhisper = TranscriptionEngineKind.WhisperKit

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.quietNow(): Double = 1_000.0 + currentTime / 1000.0

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.quietAdvanceUntil(limitSeconds: Int, condition: () -> Boolean): Boolean {
    var waited = 0
    while (!condition() && waited < limitSeconds * 20) {
        advanceTimeBy(50)
        runCurrent()
        waited += 1
    }
    return condition()
}

private class QuietChecks {
    val planned = ArrayList<Pair<Double, Double>>()
}

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionPipelineQuietTickTest {
    private fun TestScope.quiet(engine: FakeEngine, watchdog: AudioStallWatchdog): Pair<MadePipeline, QuietChecks> {
        val made = makePipeline(
            engines = mapOf(quietWhisper to engine),
            audioWatchdog = watchdog,
            now = { quietNow() },
        )
        val checks = QuietChecks()
        made.pipeline.staleCheckObserver = { seconds -> checks.planned.add(seconds to quietNow()) }
        return made to checks
    }

    private fun lineToken(text: String, at: Double) =
        TranscriptToken(utteranceID = UUID.randomUUID(), text = text, isFinal = false, timestamp = at)

    @Test
    fun `with no line open the checks run once a second`() = runTest {
        val (made, checks) = quiet(FakeEngine(), AudioStallWatchdog.disabled)
        made.pipeline.start(AppSettings.default)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(checks.planned.size >= 9)
        assertTrue(checks.planned.all { it.first == 1.0 })
        val gaps = checks.planned.zipWithNext { a, b -> b.second - a.second }
        assertTrue(gaps.all { kotlin.math.abs(it - 1.0) < 0.01 }, "gaps: $gaps")
        made.pipeline.stop()
    }

    @Test
    fun `with a line open the checks run every quarter second and the stale line commits as soon as it is due`() = runTest {
        val engine = FakeEngine()
        val (made, checks) = quiet(engine, AudioStallWatchdog.disabled)
        made.pipeline.start(AppSettings.default)
        advanceTimeBy(2_300)
        runCurrent()
        val opened = quietNow()
        engine.emit(lineToken("hello", opened))
        runCurrent()
        assertTrue(made.pipeline.stats.hasOpenLine)
        val committed = quietAdvanceUntil(20) { made.pipeline.segments.firstOrNull()?.isCommitted == true }
        val wait = quietNow() - opened
        made.pipeline.stop()
        assertTrue(committed)
        assertTrue(wait >= CaptionStabilizer.DEFAULT_SILENCE_COMMIT_THRESHOLD)
        assertTrue(wait <= CaptionStabilizer.DEFAULT_SILENCE_COMMIT_THRESHOLD + 0.3)
        val during = checks.planned.filter { it.second > opened + 0.3 && it.second < opened + CaptionStabilizer.DEFAULT_SILENCE_COMMIT_THRESHOLD }
        assertTrue(during.isNotEmpty())
        assertTrue(during.all { it.first == 0.25 })
    }

    @Test
    fun `a line that opens during a one second sleep wakes the loop at once`() = runTest {
        val engine = FakeEngine()
        val (made, checks) = quiet(engine, AudioStallWatchdog.disabled)
        made.pipeline.start(AppSettings.default)
        advanceTimeBy(2_400)
        runCurrent()
        val opened = quietNow()
        engine.emit(lineToken("hello", opened))
        runCurrent()
        val next = checks.planned.first { it.second >= opened && it.first == 0.25 }
        made.pipeline.stop()
        assertTrue(next.second - opened < 0.1)
    }

    @Test
    fun `a microphone that stops sending audio is reported after the same stall limit in a one second rhythm`() = runTest {
        val (made, _) = quiet(FakeEngine(), AudioStallWatchdog(stallSeconds = 6.0))
        made.pipeline.start(AppSettings.default)
        val start = quietNow()
        assertTrue(quietAdvanceUntil(30) { made.pipeline.stats.audioStalls == 1 })
        val after = quietNow() - start
        made.pipeline.stop()
        assertTrue(after >= 6.0, "reported early: $after")
        assertTrue(after <= 7.25, "reported late: $after")
    }

    @Test
    fun `a line opening in the middle of a stall changes neither the limit nor the report`() = runTest {
        val engine = FakeEngine()
        val (made, checks) = quiet(engine, AudioStallWatchdog(stallSeconds = 6.0))
        made.pipeline.start(AppSettings.default)
        val start = quietNow()
        advanceTimeBy(2_500)
        runCurrent()
        engine.emit(lineToken("late", quietNow()))
        runCurrent()
        assertTrue(quietAdvanceUntil(30) { made.pipeline.stats.audioStalls == 1 })
        val after = quietNow() - start
        made.pipeline.stop()
        assertTrue(after >= 6.0, "reported early: $after")
        assertTrue(after <= 7.25, "reported late: $after")
        assertTrue(checks.planned.any { it.first == 0.25 })
        assertEquals(1, made.pipeline.stats.audioStalls)
    }
}
