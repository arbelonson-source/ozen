package com.arbelonson.ozen

import com.arbelonson.ozen.core.PhoneModelSwitch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordedLock : TimedLock {
    val calls = mutableListOf<String>()

    override fun acquire(timeoutMillis: Long) {
        calls += "acquire $timeoutMillis"
    }

    override fun release() {
        calls += "release"
    }
}

class DownloadWakeLockTest {
    private val raw = RecordedLock()
    private val wake = DownloadWakeLock(raw)

    @Test
    fun `the phone is kept awake only while bytes are coming in, and only for a limited time`() {
        wake.follow(PhoneModelSwitch.Downloading("m.bin", 0.2))
        assertTrue(wake.held)
        assertEquals(listOf("acquire ${DownloadWakeLock.TIMEOUT_MILLISECONDS}"), raw.calls)
        assertTrue(DownloadWakeLock.TIMEOUT_MILLISECONDS in 1..3_600_000)
    }

    @Test
    fun `each step of the download renews the time limit, and the check of the finished file keeps the lock`() {
        wake.follow(PhoneModelSwitch.Downloading("m.bin", 0.2))
        wake.follow(PhoneModelSwitch.Downloading("m.bin", 0.3))
        wake.follow(PhoneModelSwitch.Downloading("m.bin", 1.0, checking = true))
        assertEquals(3, raw.calls.count { it.startsWith("acquire") })
        assertTrue(wake.held)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a download too slow to move the percent for longer than the time limit is still kept awake`() = runTest {
        var state: PhoneModelSwitch = PhoneModelSwitch.Downloading("m.bin", 0.41)
        val keeping = launch { wake.keepUp { state } }
        val stalled = DownloadWakeLock.TIMEOUT_MILLISECONDS * 3
        var lastAcquired = 0L
        var longestGap = 0L
        var at = 0L
        while (at < stalled) {
            val before = raw.calls.count { it.startsWith("acquire") }
            advanceTimeBy(1_000)
            runCurrent()
            at += 1_000
            if (raw.calls.count { it.startsWith("acquire") } > before) {
                longestGap = maxOf(longestGap, at - lastAcquired)
                lastAcquired = at
            }
        }
        longestGap = maxOf(longestGap, at - lastAcquired)
        assertTrue(longestGap < DownloadWakeLock.TIMEOUT_MILLISECONDS / 2, "the lock went $longestGap ms without being renewed")
        state = PhoneModelSwitch.WaitingForWiFi("m.bin")
        advanceTimeBy(DownloadWakeLock.TIMEOUT_MILLISECONDS)
        runCurrent()
        assertFalse(wake.held)
        keeping.cancel()
    }

    @Test
    fun `the lock is let go whenever the download is not transferring`() {
        val resting = listOf(
            PhoneModelSwitch.Idle,
            PhoneModelSwitch.Failed("m.bin"),
            PhoneModelSwitch.Retrying("m.bin", 15),
            PhoneModelSwitch.WaitingForWiFi("m.bin"),
            PhoneModelSwitch.Offline("m.bin"),
            PhoneModelSwitch.NotEnoughRoom("m.bin", 10),
        )
        for (state in resting) {
            wake.follow(PhoneModelSwitch.Downloading("m.bin", 0.5))
            wake.follow(state)
            assertFalse(wake.held, "held during $state")
            assertEquals("release", raw.calls.last(), "not released for $state")
        }
    }

    @Test
    fun `letting go twice, or when nothing was held, never releases a lock that is not held`() {
        wake.release()
        wake.follow(PhoneModelSwitch.Idle)
        assertTrue(raw.calls.isEmpty())
        wake.follow(PhoneModelSwitch.Downloading("m.bin", 0.5))
        wake.release()
        wake.release()
        assertEquals(1, raw.calls.count { it == "release" })
        assertFalse(wake.held)
    }
}
