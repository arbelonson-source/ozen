package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundCleanupTest {
    private var loaded = 1
    private var loading = 0
    private var restful = true
    private var allowed = true
    private var rests = 0
    private val released = mutableListOf<Long>()

    private fun TestScope.cleanup(seconds: Long = 120) = BackgroundCleanup(
        scope = this,
        seconds = seconds,
        clock = { testScheduler.currentTime },
        restVoice = { rests++ },
        voiceResting = { restful },
        loadedModels = { loaded },
        loadingModels = { loading },
        mayRelease = { allowed },
        release = {
            released += it
            loaded = 0
        },
    )

    @Test
    fun theModelIsReleasedAfterTheDelayAndNothingWakesAfterwards() = runTest {
        val cleanup = cleanup()
        cleanup.start()
        advanceTimeBy(119_000)
        assertTrue(released.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, released.size)
        assertFalse(cleanup.isWatching)
        val restsAfterRelease = rests
        advanceTimeBy(8 * 3_600_000L)
        assertEquals(restsAfterRelease, rests)
    }

    @Test
    fun aModelLoadedAgainInTheBackgroundIsReleasedAfterTheUsualDelay() = runTest {
        val cleanup = cleanup()
        cleanup.start()
        advanceTimeBy(150_000)
        runCurrent()
        assertEquals(1, released.size)
        advanceTimeBy(600_000)
        loaded = 1
        allowed = false
        cleanup.start()
        advanceTimeBy(3_600_000)
        assertEquals(1, released.size)
        allowed = true
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(2, released.size)
        assertFalse(cleanup.isWatching)
    }

    @Test
    fun aWatchStartedBeforeTheDelayStillReleasesAtTheDelay() = runTest {
        loaded = 0
        val cleanup = cleanup()
        cleanup.start()
        advanceTimeBy(31_000)
        runCurrent()
        assertFalse(cleanup.isWatching)
        loaded = 1
        advanceTimeBy(60_000)
        cleanup.start()
        advanceTimeBy(29_000)
        assertTrue(released.isEmpty())
        advanceTimeBy(32_000)
        runCurrent()
        assertEquals(1, released.size)
    }

    @Test
    fun theVoiceKeepsBeingRestedUntilItLetsGo() = runTest {
        loaded = 0
        restful = false
        val cleanup = cleanup()
        cleanup.start()
        advanceTimeBy(95_000)
        assertEquals(3, rests)
        assertTrue(cleanup.isWatching)
        restful = true
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(cleanup.isWatching)
    }

    @Test
    fun aModelStillLoadingKeepsTheWatchGoingUntilItIsReleased() = runTest {
        loaded = 0
        loading = 1
        val cleanup = cleanup()
        cleanup.start()
        advanceTimeBy(95_000)
        assertTrue(cleanup.isWatching)
        loading = 0
        loaded = 1
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, released.size)
        assertFalse(cleanup.isWatching)
    }

    @Test
    fun cancellingStopsTheWatch() = runTest {
        val cleanup = cleanup()
        cleanup.start()
        cleanup.cancel()
        advanceTimeBy(3_600_000)
        assertEquals(0, rests)
        assertTrue(released.isEmpty())
    }
}
