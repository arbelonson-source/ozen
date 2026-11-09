package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private val homeKind = TranscriptionEngineKind.HomeServer
private val cloudKind = TranscriptionEngineKind.Cloud
private val phoneKind = TranscriptionEngineKind.WhisperKit

private fun oneQuickRetry() = AutoRecoveryPolicy(glitchDelays = listOf(0.01), downloadDelays = emptyList())

private class SleepWaiting(val captions: CaptionPipeline, val engine: FakeEngine)

/** Captions on a phone with no backup model, the quick retries spent, waiting on a timer for [kind] to answer. */
private suspend fun TestScope.waitingFor(kind: TranscriptionEngineKind, retryWait: RetryWait): SleepWaiting {
    val unavailable = if (kind == homeKind) EngineUnavailability.Kind.HomeServerUnreachable else EngineUnavailability.Kind.TemporarilyUnavailable
    val engine = FakeEngine(kind = kind, availability = EngineAvailability.unavailable(unavailable, "asleep"))
    val phone = FakeEngine(kind = phoneKind)
    phone.modelOnPhone = false
    val captions = captionPipeline(
        audio = FakeAudioCapturer(),
        engineFactory = { settings -> if (settings.engine == kind) engine else phone },
        recovery = oneQuickRetry(),
        retryWait = retryWait,
    )
    val notes = ArrayList<String>()
    captions.onEvent = { event -> notes.add(event.description) }
    val settings = AppSettings.default
    settings.engine = kind
    settings.homeServerAddress = "10.0.0.5"
    captions.start(settings)
    val waitNote = if (kind == homeKind) "waiting for the home computer" else "waiting for the cloud"
    assertTrue(eventually { notes.any { it.startsWith(waitNote) } }, "$notes")
    return SleepWaiting(captions, engine)
}

/** Waiting for [kind], asked every [every]: the processor stays up through three checks, and is let go once captions are back. */
private suspend fun TestScope.keptUpThroughEveryCheck(kind: TranscriptionEngineKind, every: Duration) {
    val asked = mutableListOf<Duration?>()
    val waiting = waitingFor(kind, RetryWait(sinceBoot = SleepingPhone(this).sinceBoot) { asked += it })
    val checks = waiting.engine.prepareCount
    delay(every * 3 + 5.seconds)
    assertTrue(waiting.engine.prepareCount - checks >= 3, "asked ${waiting.engine.prepareCount - checks} times")
    val whileWaiting = asked.dropWhile { it != every + RetryWait.AFTERWARDS }
    assertTrue(whileWaiting.size >= 3, "$asked")
    assertFalse(null in whileWaiting, "let go between checks: $asked")
    waiting.engine.availability = EngineAvailability.Available
    assertTrue(eventually(every + 5.seconds) { waiting.captions.phase == PipelinePhase.Listening && waiting.captions.activeEngineKind == kind })
    assertTrue(eventually { asked.last() == null }, "kept up with captions back: $asked")
    assertEquals(1, asked.dropWhile { it != every + RetryWait.AFTERWARDS }.count { it == null }, "$asked")
}

class CaptionPipelineSleepTest {
    @Test
    fun `a retry lined up for half a minute runs at the first second the phone is awake after it came due, however long it slept`() = runTest {
        val phone = SleepingPhone(this)
        val engine = FakeEngine()
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { engine },
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(30.0), downloadDelays = emptyList()),
            retryWait = RetryWait(sinceBoot = phone.sinceBoot),
        )
        captions.start(AppSettings.default)
        assertTrue(captions.phase.isListening)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { captions.scheduledRetry != null })
        delay(2.seconds)
        assertFalse(captions.phase.isListening)
        phone.sleep(5.minutes)
        assertTrue(eventually(2.seconds) { captions.phase.isListening }, "still ${captions.phase} after five minutes asleep")
    }

    @Test
    fun `while a retry is due the processor is kept up, and it is let go once captions are back`() = runTest {
        val asked = mutableListOf<Duration?>()
        val engine = FakeEngine()
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { engine },
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(30.0), downloadDelays = emptyList()),
            retryWait = RetryWait(sinceBoot = SleepingPhone(this).sinceBoot) { asked += it },
        )
        captions.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { captions.scheduledRetry != null })
        delay(1.seconds)
        assertEquals(listOf<Duration?>(30.seconds + RetryWait.AFTERWARDS), asked)
        assertTrue(eventually(40.seconds) { captions.phase.isListening })
        assertEquals(listOf(30.seconds + RetryWait.AFTERWARDS, null), asked)
    }

    @Test
    fun `the processor stays up while the retry starts the engine, not only through the wait before it`() = runTest {
        val asked = mutableListOf<Duration?>()
        val engine = FakeEngine()
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { engine },
            recovery = AutoRecoveryPolicy(glitchDelays = listOf(30.0), downloadDelays = emptyList()),
            retryWait = RetryWait(sinceBoot = SleepingPhone(this).sinceBoot) { asked += it },
        )
        captions.start(AppSettings.default)
        engine.endStream(throwing = TestError())
        assertTrue(eventually { captions.scheduledRetry != null })
        val gate = PrepareGate()
        engine.prepareGate = gate
        assertTrue(eventually(40.seconds) { engine.heldPrepares == 1 }, "the retry never reached the engine: ${captions.phase}")
        assertEquals(listOf<Duration?>(30.seconds + RetryWait.AFTERWARDS), asked, "let go while the engine starts")
        gate.open()
        assertTrue(eventually { captions.phase.isListening })
        assertEquals(listOf(30.seconds + RetryWait.AFTERWARDS, null), asked)
    }

    @Test
    fun `a home computer that wakes while the phone sleeps is asked at the first second the phone is awake, and captions start`() = runTest {
        val phone = SleepingPhone(this)
        val waiting = waitingFor(homeKind, RetryWait(sinceBoot = phone.sinceBoot))
        delay(3.seconds)
        val asked = waiting.engine.prepareCount
        waiting.engine.availability = EngineAvailability.Available
        phone.sleep(10.minutes)
        assertTrue(
            eventually(2.seconds) { waiting.captions.phase == PipelinePhase.Listening && waiting.captions.activeEngineKind == homeKind },
            "still ${waiting.captions.phase}, asked ${waiting.engine.prepareCount - asked} more times",
        )
    }

    @Test
    fun `a cloud that recovers while the phone sleeps is asked at the first second the phone is awake, and captions start`() = runTest {
        val phone = SleepingPhone(this)
        val waiting = waitingFor(cloudKind, RetryWait(sinceBoot = phone.sinceBoot))
        delay(3.seconds)
        val asked = waiting.engine.prepareCount
        waiting.engine.availability = EngineAvailability.Available
        phone.sleep(10.minutes)
        assertTrue(
            eventually(2.seconds) { waiting.captions.phase == PipelinePhase.Listening && waiting.captions.activeEngineKind == cloudKind },
            "still ${waiting.captions.phase}, asked ${waiting.engine.prepareCount - asked} more times",
        )
    }

    @Test
    fun `while captions wait for the computer the processor stays up through every check, and is let go once captions are back`() = runTest {
        keptUpThroughEveryCheck(homeKind, 15.seconds)
    }

    @Test
    fun `while captions wait for the cloud the processor stays up through every check, and is let go once captions are back`() = runTest {
        keptUpThroughEveryCheck(cloudKind, 30.seconds)
    }
}
