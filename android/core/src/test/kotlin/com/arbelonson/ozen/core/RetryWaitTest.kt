package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The test scheduler's time is the processor's own clock, which stops
 * while the phone sleeps; [sinceBoot] goes on counting through the sleep,
 * as `SystemClock.elapsedRealtime` does.
 */
class SleepingPhone(private val scope: TestScope) {
    private var slept = 0L
    val sinceBoot: () -> Long = { scope.testScheduler.currentTime + slept }

    fun sleep(duration: Duration) {
        slept += duration.inWholeMilliseconds
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RetryWaitTest {
    @Test
    fun `a wait that comes due while the phone sleeps ends as soon as it is awake again, not after the same time awake`() = runTest {
        val phone = SleepingPhone(this)
        val wait = RetryWait(sinceBoot = phone.sinceBoot)
        var over = false
        launch {
            wait.wait(15.seconds)
            over = true
        }
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(over)
        phone.sleep(1.minutes)
        advanceTimeBy(RetryWait.STEP_MILLIS)
        runCurrent()
        assertTrue(over, "still waiting after a minute asleep and ${testScheduler.currentTime} ms awake")
    }

    @Test
    fun `awake all the while, it waits the whole time and not a moment less`() = runTest {
        val phone = SleepingPhone(this)
        val wait = RetryWait(sinceBoot = phone.sinceBoot)
        var endedAt: Long? = null
        launch {
            wait.wait(15.seconds)
            endedAt = testScheduler.currentTime
        }
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(null, endedAt, "over at $endedAt ms, ${15_000 - (endedAt ?: 0)} ms early")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(15_000L, endedAt)
    }

    @Test
    fun `the processor is kept up for the wait and a little after it, for the try it leads to, then let go once`() = runTest {
        val asked = mutableListOf<Duration?>()
        val wait = RetryWait(sinceBoot = SleepingPhone(this).sinceBoot) { asked += it }
        launch { wait.wait(15.seconds) }
        runCurrent()
        assertEquals(listOf<Duration?>(15.seconds + RetryWait.AFTERWARDS), asked)
        assertTrue(RetryWait.AFTERWARDS in 30.seconds..5.minutes)
        advanceUntilIdle()
        assertEquals(listOf(15.seconds + RetryWait.AFTERWARDS, null), asked)
    }

    @Test
    fun `a wait called off lets the processor sleep again`() = runTest {
        val asked = mutableListOf<Duration?>()
        val wait = RetryWait(sinceBoot = SleepingPhone(this).sinceBoot) { asked += it }
        val waiting = launch { wait.wait(15.seconds) }
        advanceTimeBy(3_000)
        runCurrent()
        waiting.cancel()
        runCurrent()
        assertEquals(listOf(15.seconds + RetryWait.AFTERWARDS, null), asked)
    }

    @Test
    fun `waits in a row with a check after each keep the processor up throughout, renewed at each wait, and let it go at the end`() = runTest {
        val asked = mutableListOf<Duration?>()
        val wait = RetryWait(sinceBoot = SleepingPhone(this).sinceBoot) { asked += it }
        launch {
            wait.whileDue {
                repeat(3) {
                    wait.wait(15.seconds)
                    delay(5_000)
                }
            }
        }
        advanceUntilIdle()
        assertEquals(List<Duration?>(3) { 15.seconds + RetryWait.AFTERWARDS } + null, asked)
    }

    @Test
    fun `a wait that lets the processor sleep lets go of it, ends at the first second the phone is awake after it came due, and holds it again for the check after it`() = runTest {
        val phone = SleepingPhone(this)
        val asked = mutableListOf<Duration?>()
        val wait = RetryWait(sinceBoot = phone.sinceBoot) { asked += it }
        var over = false
        launch {
            wait.whileDue {
                wait.wait(15.seconds)
                wait.wait(5.minutes, keepUp = false)
                over = true
                delay(5_000)
            }
        }
        advanceTimeBy(17_000)
        runCurrent()
        assertEquals(listOf(15.seconds + RetryWait.AFTERWARDS, null), asked, "kept up through a wait that should let the phone sleep")
        phone.sleep(10.minutes)
        advanceTimeBy(RetryWait.STEP_MILLIS)
        runCurrent()
        assertTrue(over, "still waiting after ten minutes asleep")
        assertEquals(listOf(15.seconds + RetryWait.AFTERWARDS, null, RetryWait.AFTERWARDS), asked)
        advanceUntilIdle()
        assertEquals(listOf(15.seconds + RetryWait.AFTERWARDS, null, RetryWait.AFTERWARDS, null), asked)
    }

    @Test
    fun `a nudge ends at once the wait that asked for one, and no other`() = runTest {
        val wait = RetryWait(sinceBoot = SleepingPhone(this).sinceBoot)
        var nudged: Boolean? = null
        var plainOver = false
        launch { nudged = wait.wait(5.minutes, nudgedSince = wait.nudged) }
        launch {
            wait.wait(5.minutes)
            plainOver = true
        }
        advanceTimeBy(3_000)
        runCurrent()
        wait.nudge()
        runCurrent()
        assertEquals(true, nudged, "still waiting after a nudge")
        assertFalse(plainOver)
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertTrue(plainOver)
    }

    @Test
    fun `a nudge that came between two waits ends the next one at once, and a wait that runs its course says it was not nudged`() = runTest {
        val wait = RetryWait(sinceBoot = SleepingPhone(this).sinceBoot)
        val seen = wait.nudged
        wait.nudge()
        val started = testScheduler.currentTime
        assertTrue(wait.wait(5.minutes, keepUp = false, nudgedSince = seen), "the nudge was missed")
        assertEquals(started, testScheduler.currentTime)
        assertFalse(wait.wait(15.seconds, nudgedSince = wait.nudged))
        assertEquals(started + 15_000, testScheduler.currentTime)
    }
}
