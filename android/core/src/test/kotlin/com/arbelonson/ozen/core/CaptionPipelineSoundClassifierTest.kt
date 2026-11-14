package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

private fun chunk() = FloatArray(1_024) { 0.1f }

private class FlakySoundDetector(broken: Boolean) : SoundEventDetecting {
    @Volatile
    var broken = broken

    override fun observations(audio: Flow<FloatArray>): Flow<SoundObservation> = observations(audio) {}

    override fun observations(audio: Flow<FloatArray>, availability: (Boolean) -> Unit): Flow<SoundObservation> = flow {
        val supervisor = SoundClassifierSupervisor(RestartBudget(5, 600.0, 15.0))
        var working = !broken
        var clock = 0.0
        audio.collect {
            clock += 100.0
            if (working && broken) working = false
            if (supervisor.step(working, clock) == SoundClassifierSupervisor.Step.Restart) {
                working = !broken
                if (!working) supervisor.restartGaveNoClassifier()
            }
            supervisor.takeChange()?.let(availability)
        }
    }
}

private class ReportingSoundDetector : SoundEventDetecting {
    private val reporters = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    val runs: Int get() = reporters.size

    fun report(available: Boolean, run: Int) = reporters[run](available)

    override fun observations(audio: Flow<FloatArray>): Flow<SoundObservation> = observations(audio) {}

    override fun observations(audio: Flow<FloatArray>, availability: (Boolean) -> Unit): Flow<SoundObservation> {
        reporters += availability
        return flow { audio.collect {} }
    }
}

class CaptionPipelineSoundClassifierTest {
    @Test
    fun `a classifier that keeps failing is reported by the pipeline, which stops saying sound detection runs`() = runTest {
        val detector = FlakySoundDetector(broken = false)
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        assertTrue(pipeline.stats.soundDetectionRunning)
        assertFalse(pipeline.soundAlertsNotWorking)

        detector.broken = true
        repeat(8) { audio.push(chunk()) }
        assertTrue(eventually { pipeline.soundAlertsNotWorking })
        assertFalse(pipeline.stats.soundDetectionRunning)
        assertTrue(pipeline.phase.isListening)
    }

    @Test
    fun `sound alerts are reported working again once the classifier works`() = runTest {
        val detector = FlakySoundDetector(broken = true)
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        repeat(8) { audio.push(chunk()) }
        assertTrue(eventually { pipeline.soundAlertsNotWorking })

        detector.broken = false
        repeat(10) { audio.push(chunk()) }
        assertTrue(eventually { !pipeline.soundAlertsNotWorking })
        assertTrue(pipeline.stats.soundDetectionRunning)
    }

    @Test
    fun `turning sound alerts off takes the not-working report away, and a healthy classifier never raises it`() = runTest {
        val healthy = FlakySoundDetector(broken = false)
        val (steady, steadyAudio, _) = makePipeline(soundDetector = healthy)
        steady.start(AppSettings.default)
        repeat(8) { steadyAudio.push(chunk()) }
        assertFalse(eventually(kotlin.time.Duration.parse("200ms")) { steady.soundAlertsNotWorking })
        assertTrue(steady.stats.soundDetectionRunning)

        val detector = FlakySoundDetector(broken = true)
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { pipeline.soundAlertsNotWorking })
        pipeline.setSoundAlertPreferences(SoundAlertPreferences(isEnabled = false))
        assertFalse(pipeline.soundAlertsNotWorking)
    }

    @Test
    fun `sounds-only listening that ended with the classifier failing does not carry that into what comes next`() = runTest {
        val detector = ReportingSoundDetector()
        val (pipeline, _, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        pipeline.listenForSoundsOnly()
        assertTrue(pipeline.isListeningForSoundsOnly)
        detector.report(false, detector.runs - 1)
        assertTrue(eventually { pipeline.soundAlertsNotWorking })
        assertFalse(pipeline.stats.soundDetectionRunning)

        pipeline.systemInterruptionChanged(true)
        assertFalse(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.soundAlertsNotWorking)

        pipeline.systemInterruptionChanged(false)
        assertTrue(eventually { pipeline.isListeningForSoundsOnly })
        assertFalse(pipeline.soundAlertsNotWorking)
        assertTrue(pipeline.stats.soundDetectionRunning)
    }

    @Test
    fun `a report from the classifier of an earlier run is ignored, and the current run's is not`() = runTest {
        val detector = ReportingSoundDetector()
        val (pipeline, _, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        val old = detector.runs - 1
        pipeline.stop()
        pipeline.start(AppSettings.default)
        assertTrue(detector.runs - 1 > old)

        detector.report(false, old)
        assertFalse(eventually(kotlin.time.Duration.parse("200ms")) { pipeline.soundAlertsNotWorking })
        assertTrue(pipeline.stats.soundDetectionRunning)

        detector.report(false, detector.runs - 1)
        assertTrue(eventually { pipeline.soundAlertsNotWorking })
    }
}
