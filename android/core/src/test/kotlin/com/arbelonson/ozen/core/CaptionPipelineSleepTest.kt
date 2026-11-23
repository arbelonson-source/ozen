package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
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
private suspend fun TestScope.waitingFor(
    kind: TranscriptionEngineKind,
    retryWait: RetryWait,
    network: NetworkMonitoring? = null,
    watched: Boolean = false,
    onNote: (String) -> Unit = {},
): SleepWaiting {
    val unavailable = if (kind == homeKind) EngineUnavailability.Kind.HomeServerUnreachable else EngineUnavailability.Kind.TemporarilyUnavailable
    val engine = FakeEngine(kind = kind, availability = EngineAvailability.unavailable(unavailable, "asleep"))
    val phone = FakeEngine(kind = phoneKind)
    phone.modelOnPhone = false
    val captions = captionPipeline(
        audio = FakeAudioCapturer(),
        engineFactory = { settings -> if (settings.engine == kind) engine else phone },
        recovery = oneQuickRetry(),
        network = network,
        retryWait = retryWait,
    )
    captions.watched = watched
    val notes = ArrayList<String>()
    captions.onEvent = { event ->
        notes.add(event.description)
        onNote(event.description)
    }
    val settings = AppSettings.default
    settings.engine = kind
    if (kind == homeKind) settings.homeServerAddress = "10.0.0.5"
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

/**
 * Waiting an hour for [kind], first asked every [every]: the checks come
 * further apart after the first two minutes, never more than five minutes
 * apart, and the processor is kept up for about the first ten minutes
 * only. Kept up all the while, a phone left with the computer off at
 * home was kept from sleeping all day.
 */
private suspend fun TestScope.easesOffThroughAnHour(kind: TranscriptionEngineKind, every: Duration) {
    val phone = SleepingPhone(this)
    val asked = mutableListOf<Pair<Long, Duration?>>()
    val waiting = waitingFor(kind, RetryWait(sinceBoot = phone.sinceBoot) { asked += phone.sinceBoot() to it })
    val checks = mutableListOf<Long>()
    waiting.engine.duringPrepare = { checks += phone.sinceBoot() }
    delay(1.hours)
    val gaps = checks.zipWithNext { a, b -> (b - a).milliseconds }
    assertTrue(gaps.take(3).all { it in every..every + 1.seconds }, "$gaps")
    assertTrue(gaps.zipWithNext().all { (a, b) -> b >= a - 1.seconds }, "closer together again: $gaps")
    assertTrue(gaps.takeLast(5).all { it in 5.minutes..5.minutes + 1.seconds }, "$gaps")
    assertTrue(checks.size <= 30, "asked ${checks.size} times in an hour: $gaps")
    assertEquals(null, asked.last().second, "still kept up after an hour")
    var held = Duration.ZERO
    asked.zipWithNext { (at, ask), (next, _) -> if (ask != null) held += (next - at).milliseconds }
    assertTrue(held in 8.minutes..11.minutes, "kept up for $held of the hour")
}

/**
 * Waiting long enough for [kind] that the checks are five minutes apart:
 * joining another network asks at once, and the checks start over at the
 * quick pace. Losing the connection is no reason to ask.
 */
private suspend fun TestScope.askedAtOnceOnANewNetwork(kind: TranscriptionEngineKind, every: Duration) {
    val phone = SleepingPhone(this)
    val network = FakeNetworkMonitor(NetworkConditions.cellular)
    val waiting = waitingFor(kind, RetryWait(sinceBoot = phone.sinceBoot), network)
    delay(30.minutes)
    val checks = mutableListOf<Long>()
    waiting.engine.duringPrepare = { checks += phone.sinceBoot() }
    assertTrue(eventually(6.minutes) { checks.isNotEmpty() })
    delay(1.seconds)
    network.change(NetworkConditions.wifi)
    assertTrue(eventually(1.seconds) { checks.size == 2 }, "not asked when the phone joined Wi-Fi")
    delay(every * 2 + 1.seconds)
    val gaps = checks.zipWithNext { a, b -> (b - a).milliseconds }
    assertEquals(4, checks.size, "$gaps")
    assertTrue(gaps.drop(1).all { it in every..every + 1.seconds }, "$gaps")
    network.change(NetworkConditions.offline)
    delay(2.seconds)
    assertEquals(4, checks.size, "asked with no connection")
    waiting.engine.availability = EngineAvailability.Available
    network.change(NetworkConditions.wifi)
    assertTrue(eventually(2.seconds) { waiting.captions.phase == PipelinePhase.Listening && waiting.captions.activeEngineKind == kind })
}

/**
 * Waiting an hour for [kind] while [startOver] starts the wait again every
 * four minutes (the network dropping and coming back, a call ending): the
 * processor is still kept up for about the first ten minutes only. Each
 * new wait was given the ten minutes again, so a connection that kept
 * dropping kept the phone from sleeping all day.
 */
private suspend fun TestScope.keptUpOnceThroughRestarts(kind: TranscriptionEngineKind, startOver: suspend (CaptionPipeline, FakeNetworkMonitor) -> Unit) {
    val phone = SleepingPhone(this)
    val asked = mutableListOf<Pair<Long, Duration?>>()
    val network = FakeNetworkMonitor(NetworkConditions.wifi)
    val notes = ArrayList<String>()
    val waiting = waitingFor(kind, RetryWait(sinceBoot = phone.sinceBoot) { asked += phone.sinceBoot() to it }, network, onNote = { notes += it })
    repeat(15) {
        delay(4.minutes)
        startOver(waiting.captions, network)
    }
    delay(4.minutes)
    val waitNote = if (kind == homeKind) "waiting for the home computer" else "waiting for the cloud"
    assertTrue(notes.count { it.startsWith(waitNote) } >= 15, "the wait never started again: $notes")
    assertTrue(waiting.captions.phase is PipelinePhase.Failed, "${waiting.captions.phase}")
    var held = Duration.ZERO
    asked.zipWithNext { (at, ask), (next, _) -> if (ask != null) held += (next - at).milliseconds }
    assertTrue(held <= 11.minutes, "kept up for $held of the hour")
}

/**
 * The phone joins a network just as captions stop for [kind], before the
 * wait for it has begun: it is asked at once, as for a network joined
 * during the wait. That join was missed, and the first check came a whole
 * quick step later.
 */
private suspend fun TestScope.askedAtOnceOnANewNetworkAsTheWaitBegins(kind: TranscriptionEngineKind) {
    val phone = SleepingPhone(this)
    val network = FakeNetworkMonitor(NetworkConditions.cellular)
    val waitNote = if (kind == homeKind) "waiting for the home computer" else "waiting for the cloud"
    var joinedAt: Long? = null
    var askedAt: Long? = null
    waitingFor(kind, RetryWait(sinceBoot = phone.sinceBoot), network, onNote = { note ->
        if (note.startsWith(waitNote) && joinedAt == null) {
            joinedAt = phone.sinceBoot()
            network.change(NetworkConditions.wifi)
        }
        if (note.startsWith("a new connection") && askedAt == null) askedAt = phone.sinceBoot()
    })
    assertTrue(eventually(2.seconds) { askedAt != null }, "not asked when the phone joined Wi-Fi")
    assertTrue(askedAt!! - joinedAt!! <= 1_000, "asked ${askedAt!! - joinedAt!!} ms after joining")
}

/**
 * Someone looking at the captions for the whole hour: the checks keep the
 * first, quick pace and the processor stays up. A computer restarting
 * for an update was back for minutes before a slowed check noticed, with
 * the person watching the screen.
 */
private suspend fun TestScope.quickWhileWatched(kind: TranscriptionEngineKind, every: Duration) {
    val phone = SleepingPhone(this)
    val asked = mutableListOf<Duration?>()
    val waiting = waitingFor(kind, RetryWait(sinceBoot = phone.sinceBoot) { asked += it }, watched = true)
    val checks = mutableListOf<Long>()
    waiting.engine.duringPrepare = { checks += phone.sinceBoot() }
    delay(1.hours)
    val gaps = checks.zipWithNext { a, b -> (b - a).milliseconds }
    assertTrue(gaps.all { it in every..every + 1.seconds }, "$gaps")
    assertTrue(checks.size >= (1.hours / every).toInt() - 2, "asked ${checks.size} times")
    assertFalse(null in asked.dropWhile { it != every + RetryWait.AFTERWARDS }, "let go while watched: $asked")
}

/**
 * Out of sight long enough that the checks are five minutes apart and the
 * processor is let go: the captions back in sight ask at once and keep
 * the quick pace while they are watched; out of sight again, the checks
 * slow down again, and the processor, its ten minutes used up, stays let go.
 */
private suspend fun TestScope.askedAtOnceBackInSight(kind: TranscriptionEngineKind, every: Duration) {
    val phone = SleepingPhone(this)
    val asked = mutableListOf<Duration?>()
    val waiting = waitingFor(kind, RetryWait(sinceBoot = phone.sinceBoot) { asked += it })
    delay(30.minutes)
    val checks = mutableListOf<Long>()
    waiting.engine.duringPrepare = { checks += phone.sinceBoot() }
    assertTrue(eventually(6.minutes) { checks.isNotEmpty() })
    delay(1.seconds)
    waiting.captions.watched = true
    assertTrue(eventually(1.seconds) { checks.size == 2 }, "not asked when the captions came back in sight")
    delay(5.minutes)
    val watchedGaps = checks.zipWithNext { a, b -> (b - a).milliseconds }.drop(1)
    assertTrue(watchedGaps.size >= (5.minutes / every).toInt() - 1, "$watchedGaps")
    assertTrue(watchedGaps.all { it in every..every + 1.seconds }, "slowed down while watched: $watchedGaps")
    assertTrue(asked.last() != null, "let go while watched: $asked")
    val seen = checks.size
    waiting.captions.watched = false
    delay(10.minutes)
    val awayGaps = checks.drop(seen - 1).zipWithNext { a, b -> (b - a).milliseconds }
    assertTrue(awayGaps.last() >= every * 4, "the same quick pace out of sight: $awayGaps")
    assertTrue(eventually(2.seconds) { asked.last() == null }, "kept up out of sight again: ${asked.takeLast(5)}")
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

    @Test
    fun `waiting an hour for the computer, the checks come further apart up to five minutes, and the processor is let go after about ten minutes`() = runTest {
        easesOffThroughAnHour(homeKind, 15.seconds)
    }

    @Test
    fun `waiting an hour for the cloud, the checks come further apart up to five minutes, and the processor is let go after about ten minutes`() = runTest {
        easesOffThroughAnHour(cloudKind, 30.seconds)
    }

    @Test
    fun `a new network while captions wait for the computer asks it at once, and the checks start over at the quick pace`() = runTest {
        askedAtOnceOnANewNetwork(homeKind, 15.seconds)
    }

    @Test
    fun `a new network while captions wait for the cloud asks it at once, and the checks start over at the quick pace`() = runTest {
        askedAtOnceOnANewNetwork(cloudKind, 30.seconds)
    }

    @Test
    fun `captions back after a long wait for the computer, and the computer lost again, the checks start over at 15 s with the processor kept up`() = runTest {
        val phone = SleepingPhone(this)
        val asked = mutableListOf<Duration?>()
        val waiting = waitingFor(homeKind, RetryWait(sinceBoot = phone.sinceBoot) { asked += it })
        delay(30.minutes)
        assertEquals(null, asked.last(), "still kept up after half an hour")
        waiting.engine.availability = EngineAvailability.Available
        assertTrue(eventually(5.minutes + 2.seconds) { waiting.captions.phase == PipelinePhase.Listening && waiting.captions.activeEngineKind == homeKind })
        asked.clear()
        val checks = mutableListOf<Long>()
        waiting.engine.duringPrepare = { checks += phone.sinceBoot() }
        waiting.engine.availability = EngineAvailability.unavailable(EngineUnavailability.Kind.HomeServerUnreachable, "asleep")
        waiting.engine.endStream(throwing = EngineUnavailability(EngineUnavailability.Kind.HomeServerUnreachable, "connection lost"))
        delay(1.minutes)
        val gaps = checks.zipWithNext { a, b -> (b - a).milliseconds }
        assertTrue(checks.size >= 4, "$gaps")
        assertTrue(gaps.takeLast(3).all { it in 15.seconds..16.seconds }, "$gaps")
        val whileWaiting = asked.dropWhile { it != 15.seconds + RetryWait.AFTERWARDS }
        assertTrue(whileWaiting.size >= 3, "$asked")
        assertFalse(null in whileWaiting, "let go between checks: $asked")
    }

    @Test
    fun `the network dropping and coming back while captions wait for the computer does not keep the processor up for ten more minutes each time`() = runTest {
        keptUpOnceThroughRestarts(homeKind) { _, network ->
            network.change(NetworkConditions.offline)
            delay(1.seconds)
            network.change(NetworkConditions.wifi)
        }
    }

    @Test
    fun `calls ending while captions wait for the cloud do not keep the processor up for ten more minutes each time`() = runTest {
        keptUpOnceThroughRestarts(cloudKind) { captions, _ ->
            captions.systemInterruptionChanged(true)
            delay(1.seconds)
            captions.systemInterruptionChanged(false)
        }
    }

    @Test
    fun `a network joined just as captions stop for the computer asks it at once`() = runTest {
        askedAtOnceOnANewNetworkAsTheWaitBegins(homeKind)
    }

    @Test
    fun `a network joined just as captions stop for the cloud asks it at once`() = runTest {
        askedAtOnceOnANewNetworkAsTheWaitBegins(cloudKind)
    }

    @Test
    fun `while someone watches the captions, the computer is asked every 15 s for an hour with the processor kept up`() = runTest {
        quickWhileWatched(homeKind, 15.seconds)
    }

    @Test
    fun `while someone watches the captions, the cloud is asked every 30 s for an hour with the processor kept up`() = runTest {
        quickWhileWatched(cloudKind, 30.seconds)
    }

    @Test
    fun `captions back in sight during a long wait for the computer ask it at once, keep the quick pace, and slow down again out of sight`() = runTest {
        askedAtOnceBackInSight(homeKind, 15.seconds)
    }

    @Test
    fun `captions back in sight during a long wait for the cloud ask it at once, keep the quick pace, and slow down again out of sight`() = runTest {
        askedAtOnceBackInSight(cloudKind, 30.seconds)
    }
}
