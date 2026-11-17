package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private class Crash(text: String) : IllegalStateException(text)

private data class WaitingForRoom(
    val pipeline: CaptionPipeline,
    val detector: FakeSoundDetector,
    val audio: FakeAudioCapturer,
    val scope: CoroutineScope,
)

private fun TestScope.waitingForRoom(): WaitingForRoom {
    val detector = FakeSoundDetector()
    val audio = FakeAudioCapturer()
    val engine = FakeEngine().apply { pendingDownload = 626 }
    lateinit var made: CaptionPipeline
    val guarded = errorGuardedScope(StandardTestDispatcher(testScheduler)) { made.unexpectedError(it) }
    made = captionPipeline(
        audio = audio,
        engineFactory = { engine },
        soundDetector = detector,
        recovery = AutoRecoveryPolicy(),
        network = FakeNetworkMonitor(NetworkConditions.wifi),
        availableStorageBytes = { 300L * 1_000_000 },
        scope = guarded,
    )
    return WaitingForRoom(made, detector, audio, guarded)
}

private fun CaptionPipeline.notes(): List<String> =
    eventLog.events.map { it.kind }.filterIsInstance<PipelineEvent.Kind.Note>().map { it.text }

class CaptionPipelineUnexpectedErrorSoundsTest {
    @Test
    fun `sounds kept while captions wait for a person come back after an error stopped them`() = runTest {
        val (pipeline, detector, audio, scope) = waitingForRoom()
        try {
            pipeline.start(AppSettings.default)
            assertTrue(eventually { pipeline.isListeningForSoundsOnly })
            val reason = pipeline.phase.failure
            assertEquals(EngineUnavailability.Kind.NotEnoughStorage, reason?.engineUnavailability?.kind)
            val opened = audio.calls.count { it == "startCapture" }

            detector.fail(Crash("classifier broke"))

            assertTrue(eventually { audio.calls.count { it == "startCapture" } > opened })
            assertTrue(eventually { pipeline.isListeningForSoundsOnly })
            assertEquals(reason, pipeline.phase.failure)
            assertTrue(pipeline.phase is PipelinePhase.Failed)
            assertTrue(pipeline.notes().any { it.contains("unexpected error") && it.contains("Crash") })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sounds that are still running are left alone and only the log line is written`() = runTest {
        val (pipeline, _, audio, scope) = waitingForRoom()
        try {
            pipeline.start(AppSettings.default)
            assertTrue(eventually { pipeline.isListeningForSoundsOnly })
            val before = audio.calls.toList()
            val reason = pipeline.phase.failure

            pipeline.unexpectedError(Crash("somewhere else"))
            testScheduler.advanceTimeBy(1_000_000)
            testScheduler.runCurrent()

            assertEquals(before, audio.calls.toList())
            assertTrue(pipeline.isListeningForSoundsOnly)
            assertEquals(reason, pipeline.phase.failure)
            assertEquals(1, pipeline.notes().count { it.contains("unexpected error") })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `repeated errors wait longer each time and stop after the last wait`() = runTest {
        val (pipeline, detector, audio, scope) = waitingForRoom()
        try {
            pipeline.start(AppSettings.default)
            assertTrue(eventually { pipeline.isListeningForSoundsOnly })
            for (wait in CaptionPipeline.SOUNDS_ONLY_RETRY_SECONDS) {
                val opened = audio.calls.count { it == "startCapture" }
                val millis = (wait * 1000).toLong()
                detector.fail(Crash("again"))
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(millis - 10)
                testScheduler.runCurrent()
                assertEquals(opened, audio.calls.count { it == "startCapture" }, "restarted before $wait s")
                testScheduler.advanceTimeBy(20)
                testScheduler.runCurrent()
                assertEquals(opened + 1, audio.calls.count { it == "startCapture" }, "not restarted after $wait s")
                assertTrue(pipeline.isListeningForSoundsOnly)
            }
            detector.fail(Crash("one too many"))
            testScheduler.advanceTimeBy(1_000_000)
            testScheduler.runCurrent()
            assertTrue(pipeline.notes().any { it.contains("sound alerts stopped") })
            assertFalse(pipeline.isListeningForSoundsOnly)
            assertTrue(pipeline.phase is PipelinePhase.Failed)
        } finally {
            scope.cancel()
        }
    }
}
