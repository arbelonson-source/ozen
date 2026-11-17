package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private class Watching(val scope: TestScope) {
    var starts = 0
    var errors = 0
    var gaveUp = 0
    var alive = true
    var failures = Int.MAX_VALUE
    var failWith: () -> Throwable = { IllegalStateException("watch failed") }

    val watcher = RestartingWatcher(
        scope = scope.backgroundScope,
        now = { scope.currentTime / 1_000.0 },
        shouldRun = { alive },
        onError = { errors += 1 },
        onGaveUp = { gaveUp += 1 },
    ) {
        starts += 1
        if (starts <= failures) throw failWith()
        awaitCancellation()
    }
}

class RestartingWatcherTest {
    @Test
    fun `a body that throws an Error is restarted like an exception`() = runTest {
        val w = Watching(this).apply {
            failures = 1
            failWith = { StackOverflowError() }
        }
        w.watcher.start()
        runCurrent()
        assertEquals(1, w.errors)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(2, w.starts)
        assertTrue(w.watcher.isRunning)
    }

    @Test
    fun `a watcher that fails once is running again after the delay`() = runTest {
        val w = Watching(this).apply { failures = 1 }
        w.watcher.start()
        runCurrent()
        assertEquals(1, w.starts)
        assertEquals(1, w.errors)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, w.starts)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, w.starts)
        assertTrue(w.watcher.isRunning)
    }

    @Test
    fun `a watcher that always fails is restarted three times and then left stopped`() = runTest {
        val w = Watching(this)
        w.watcher.start()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4, w.starts)
        assertEquals(4, w.errors)
        assertEquals(1, w.gaveUp)
        assertFalse(w.watcher.isRunning)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(4, w.starts)
    }

    @Test
    fun `after the window passes a failing watcher gets its restarts again`() = runTest {
        val w = Watching(this).apply { failures = 4 }
        w.watcher.start()
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(w.watcher.isRunning)
        advanceTimeBy(600_000)
        w.watcher.start()
        runCurrent()
        assertEquals(5, w.starts)
        assertTrue(w.watcher.isRunning)
    }

    @Test
    fun `no restart runs once the service is finished`() = runTest {
        val w = Watching(this).apply { failures = 1 }
        w.watcher.start()
        runCurrent()
        w.alive = false
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, w.starts)
    }

    @Test
    fun `stopping cancels a restart that is waiting`() = runTest {
        val w = Watching(this).apply { failures = 1 }
        w.watcher.start()
        runCurrent()
        w.watcher.stop()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, w.starts)
        assertEquals(0, w.gaveUp)
    }
}
