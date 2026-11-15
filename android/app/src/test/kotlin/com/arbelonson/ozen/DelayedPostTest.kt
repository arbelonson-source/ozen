package com.arbelonson.ozen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DelayedPostTest {
    private val calls = mutableListOf<String>()
    private val awake = KeptAwake(
        object : TimedLock {
            override fun acquire(timeoutMillis: Long) {
                calls += "acquire $timeoutMillis"
            }

            override fun release() {
                calls += "release"
            }
        },
    )
    private val scope = TestScope()
    private val posted = mutableListOf<String>()
    private val wait = DelayedPost(scope, awake)

    @Test
    fun `the lock is held during the wait for a little longer than it and let go once the notice is posted`() {
        wait.schedule(60.0) { posted += "a" }
        scope.runCurrent()
        assertTrue(awake.held)
        scope.advanceTimeBy(59_000)
        scope.runCurrent()
        assertTrue(awake.held)
        assertTrue(posted.isEmpty())
        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertEquals(listOf("a"), posted)
        assertFalse(awake.held)
        assertEquals(listOf("acquire 90000", "release"), calls)
    }

    @Test
    fun `cancelling releases the lock and posts nothing`() {
        wait.schedule(60.0) { posted += "a" }
        scope.runCurrent()
        wait.cancel()
        scope.advanceTimeBy(120_000)
        scope.runCurrent()
        assertTrue(posted.isEmpty())
        assertFalse(awake.held)
        assertEquals(listOf("acquire 90000", "release"), calls)
    }

    @Test
    fun `replacing a wait keeps one lock held for the new wait only`() {
        wait.schedule(60.0) { posted += "old" }
        scope.runCurrent()
        scope.advanceTimeBy(10_000)
        wait.schedule(30.0) { posted += "new" }
        scope.runCurrent()
        assertTrue(awake.held)
        scope.advanceTimeBy(31_000)
        scope.runCurrent()
        assertEquals(listOf("new"), posted)
        assertFalse(awake.held)
        assertEquals("release", calls.last())
        assertEquals(1, calls.count { it == "acquire 60000" })
    }

    @Test
    fun `closing the scope with a wait running releases the lock`() {
        wait.schedule(60.0) { posted += "a" }
        scope.runCurrent()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        scope.runCurrent()
        assertFalse(awake.held)
    }
}
