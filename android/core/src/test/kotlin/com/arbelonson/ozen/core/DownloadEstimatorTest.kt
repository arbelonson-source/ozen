package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class DownloadEstimatorTest {
    @Test
    fun `a steady download is estimated from its pace`() {
        val estimator = DownloadEstimator()
        // 1% a second.
        for (second in 0..10) {
            estimator.record(fraction = second / 100.0, time = 1_000.0 + second)
        }
        val left = assertNotNull(estimator.secondsRemaining())
        assertTrue(abs(left - 90) < 0.5)
    }

    @Test
    fun `no estimate in the first seconds, or while nothing moves`() {
        val estimator = DownloadEstimator()
        assertNull(estimator.secondsRemaining())
        estimator.record(fraction = 0.1, time = 0.0)
        estimator.record(fraction = 0.12, time = 3.0)
        assertNull(estimator.secondsRemaining())

        val stalled = DownloadEstimator()
        stalled.record(fraction = 0.4, time = 0.0)
        stalled.record(fraction = 0.4, time = 20.0)
        assertNull(stalled.secondsRemaining())
    }

    @Test
    fun `the estimate follows the recent pace, not the average since the start`() {
        val estimator = DownloadEstimator()
        // Two minutes at 0.1% a second on a poor connection...
        for (second in 0..120 step 5) {
            estimator.record(fraction = second / 1_000.0, time = second.toDouble())
        }
        // ...then half a minute at 1% a second on better Wi-Fi.
        for (second in 125..160 step 5) {
            estimator.record(fraction = 0.12 + (second - 120) / 100.0, time = second.toDouble())
        }
        val left = assertNotNull(estimator.secondsRemaining())
        // 48% left at 1%/s is 48 s; the whole-download average would say several minutes.
        assertTrue(left < 60)
        assertTrue(left > 40)
    }

    @Test
    fun `a stall longer than the window doesn't leave the estimate anchored to pre-stall progress`() {
        val estimator = DownloadEstimator()
        estimator.record(fraction = 0.1, time = 0.0)
        // A ten-minute stall -- no progress reported at all.
        estimator.record(fraction = 0.11, time = 600.0)
        estimator.record(fraction = 0.5, time = 605.0)
        val left = assertNotNull(estimator.secondsRemaining())
        // True recent pace is (0.5-0.11)/5, about 7.8%/s, about 6.4s left.
        // Anchored to the stale sample from before the stall instead, the
        // span balloons to 605s and the estimate to well over ten minutes.
        assertTrue(left < 15)
    }

    @Test
    fun `a resumed download's first reading is where it picked up, not how fast it goes`() {
        val estimator = DownloadEstimator()
        // Announced at 0%, then the part already on the phone: 57%.
        estimator.record(fraction = 0.0, time = 0.0)
        estimator.record(fraction = 0.57, time = 1.0)
        // Then 0.1% a second.
        for (second in 2..6) {
            estimator.record(fraction = 0.57 + (second - 1) / 1_000.0, time = second.toDouble())
        }
        val left = assertNotNull(estimator.secondsRemaining())
        assertTrue(abs(left - 425) < 5)
    }

    @Test
    fun `silence counts as news only when it is much longer than the usual gap between reports`() {
        val estimator = DownloadEstimator()
        assertEquals(30.0, estimator.quietLimit(floor = 30.0, gaps = 3.0))
        for (second in 0..60 step 20) {
            estimator.record(fraction = second / 1_000.0, time = second.toDouble())
        }
        assertEquals(60.0, estimator.quietLimit(floor = 30.0, gaps = 3.0))
        assertEquals(90.0, estimator.quietLimit(floor = 90.0, gaps = 3.0))

        val quick = DownloadEstimator()
        for (second in 0..10) {
            quick.record(fraction = second / 100.0, time = second.toDouble())
        }
        assertEquals(30.0, quick.quietLimit(floor = 30.0, gaps = 3.0))
    }

    @Test
    fun `a download that starts over starts the estimate over`() {
        val estimator = DownloadEstimator()
        estimator.record(fraction = 0.5, time = 0.0)
        estimator.record(fraction = 0.6, time = 10.0)
        estimator.record(fraction = 0.0, time = 11.0)
        estimator.record(fraction = 0.01, time = 13.0)
        assertNull(estimator.secondsRemaining())
        // The new download's own pace, not the old one's higher readings.
        estimator.record(fraction = 0.11, time = 23.0)
        val left = assertNotNull(estimator.secondsRemaining())
        assertTrue(abs(left - 89) < 0.5)
    }

    @Test
    fun `reset forgets the pace, so a new download is timed afresh`() {
        val estimator = DownloadEstimator()
        estimator.record(fraction = 0.1, time = 0.0)
        estimator.record(fraction = 0.2, time = 10.0)
        assertNotNull(estimator.secondsRemaining())
        estimator.reset()
        assertNull(estimator.secondsRemaining())
    }

    @Test
    fun `two estimators with the same readings are equal`() {
        val a = DownloadEstimator()
        val b = DownloadEstimator()
        assertEquals(a, b)
        a.record(fraction = 0.1, time = 0.0)
        assertTrue(a != b)
        b.record(fraction = 0.1, time = 0.0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}

private class StepClock {
    private var time = 1_000.0

    @Synchronized
    fun tick(): Double {
        time += 3
        return time
    }
}

private fun downloading(vararg fractions: Double) = fractions.map {
    EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = it)
}

private fun TestScope.estimatePipeline(engineFor: () -> FakeEngine, clock: StepClock): CaptionPipeline = captionPipeline(
    audio = FakeAudioCapturer(),
    engineFactory = { engineFor() },
    embedder = FakeEmbedder(),
    recovery = AutoRecoveryPolicy.disabled(),
    audioWatchdog = AudioStallWatchdog.disabled,
    now = { clock.tick() },
)

class CaptionPipelineDownloadEstimateTest {
    @Test
    fun `the time left goes away when progress stops coming, as while the model is set up at the end`() = runTest {
        val engine = FakeEngine(progressUpdates = downloading(0.1, 0.2, 0.3, 0.4))
        val gate = PrepareGate()
        engine.afterProgressGate = gate
        val pipeline = estimatePipeline({ engine }, StepClock())
        pipeline.downloadQuietSeconds = 0.3
        pipeline.downloadQuietGaps = 0.0
        val start = launch { pipeline.start(AppSettings.default) }
        assertTrue(eventually { pipeline.downloadSecondsRemaining != null })
        assertTrue(eventually(within = 5.seconds) { pipeline.downloadSecondsRemaining == null })
        gate.open()
        start.join()
    }

    @Test
    fun `a start waiting on an abandoned download keeps that download's time left up to date`() = runTest {
        val engine = FakeEngine(progressUpdates = downloading(0.1, 0.2, 0.3, 0.4))
        val held = PrepareGate()
        val rest = PrepareGate()
        engine.prepareGate = held
        engine.afterProgressGate = rest
        val pipeline = estimatePipeline({ engine }, StepClock())
        val first = launch { pipeline.start(AppSettings.default) }
        while (engine.prepareCount == 0) yield()
        pipeline.stop()
        val second = launch { pipeline.start(AppSettings.default) }
        repeat(50) { yield() }
        held.open()
        val last = EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.4)
        assertTrue(eventually { pipeline.phase == PipelinePhase.PreparingEngine(last) })
        assertTrue(pipeline.downloadSecondsRemaining != null)
        rest.open()
        first.join()
        second.join()
    }

    @Test
    fun `a download that keeps reporting keeps its time left`() = runTest {
        val updates = (1..40).map {
            EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = it.toDouble() / 100)
        }
        val engine = FakeEngine(progressUpdates = updates)
        engine.progressSpacing = 50.milliseconds
        val gate = PrepareGate()
        engine.afterProgressGate = gate
        val pipeline = estimatePipeline({ engine }, StepClock())
        pipeline.downloadQuietSeconds = 1.5
        pipeline.downloadQuietGaps = 0.0
        val start = launch { pipeline.start(AppSettings.default) }
        assertTrue(eventually { pipeline.downloadSecondsRemaining != null })
        var dropped = false
        while (pipeline.phase != PipelinePhase.PreparingEngine(updates[updates.size - 1])) {
            if (pipeline.downloadSecondsRemaining == null) dropped = true
            delay(5.milliseconds)
        }
        assertFalse(dropped)
        gate.open()
        start.join()
    }

    @Test
    fun `a model download reports how long it has left, and a new start times it afresh`() = runTest {
        val downloading = FakeEngine(progressUpdates = downloading(0.1, 0.2, 0.3, 0.4))
        var current = downloading
        val pipeline = estimatePipeline({ current }, StepClock())
        var left: Double? = null
        downloading.duringPrepare = { left = pipeline.downloadSecondsRemaining }
        pipeline.start(AppSettings.default)
        assertTrue((left ?: 0.0) > 0)

        // Another model, already on the phone: nothing left over from before.
        pipeline.stop()
        val ready = FakeEngine(kind = TranscriptionEngineKind.AppleSpeech)
        current = ready
        left = -1.0
        ready.duringPrepare = { left = pipeline.downloadSecondsRemaining }
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.AppleSpeech
        pipeline.start(settings)
        assertNull(left)
    }
}
