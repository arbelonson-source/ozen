package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtherAppWaitTest {
    @Test
    fun `the first silencing resumes at once and starts the wait`() {
        val wait = OtherAppWait()
        assertTrue(wait.interrupted(0.0))
        assertEquals(0.0, wait.nextDelaySeconds())
        assertTrue(wait.waiting)
    }

    @Test
    fun `a resume silenced again waits longer each time up to the cap, and the wait begins only once`() {
        val wait = OtherAppWait()
        wait.interrupted(0.0)
        val delays = ArrayList<Double>()
        var now = 1.0
        repeat(6) {
            wait.resumed(now)
            now += 1.0
            assertFalse(wait.interrupted(now))
            delays.add(wait.nextDelaySeconds())
            now += delays.last()
        }
        assertEquals(listOf(3.0, 10.0, 10.0, 10.0, 10.0, 10.0), delays)
    }

    @Test
    fun `a resume that holds for long enough ends the wait and the next silencing starts over`() {
        val wait = OtherAppWait()
        wait.interrupted(0.0)
        wait.resumed(1.0)
        wait.interrupted(2.0)
        wait.resumed(5.0)
        assertFalse(wait.held(9.0))
        assertTrue(wait.held(10.0))
        assertFalse(wait.waiting)
        assertTrue(wait.interrupted(60.0))
        assertEquals(0.0, wait.nextDelaySeconds())
    }

    @Test
    fun `a silencing after a long held resume resets the attempts even before held was asked`() {
        val wait = OtherAppWait()
        wait.interrupted(0.0)
        wait.resumed(1.0)
        wait.interrupted(2.0)
        wait.resumed(5.0)
        wait.interrupted(40.0)
        assertEquals(0, wait.attempts)
    }
}
