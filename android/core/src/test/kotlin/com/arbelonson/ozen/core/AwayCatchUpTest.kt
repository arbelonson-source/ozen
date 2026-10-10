package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwayCatchUpTest {
    private fun lines(vararg times: Double) = times.map {
        TranscriptSegment(id = UUID.randomUUID(), text = "line", isCommitted = true, startTimestamp = it, lastUpdateTimestamp = it)
    }

    @Test
    fun `lines that began while she was away are marked from the first of them`() {
        val catchUp = AwayCatchUp()
        catchUp.screenLeft(100.0)
        catchUp.screenReturned(400.0)
        val segments = lines(50.0, 90.0, 150.0, 200.0, 390.0, 410.0)
        assertEquals(2, catchUp.firstMissedIndex(segments))
        assertEquals(3, catchUp.missedLineCount(segments))
        assertTrue(catchUp.offersJump(segments))
    }

    @Test
    fun `drawn from the first missed line still on screen, and not at all once they have all scrolled out of it`() {
        val catchUp = AwayCatchUp()
        catchUp.screenLeft(100.0)
        catchUp.screenReturned(400.0)
        val segments = lines(50.0, 150.0, 200.0, 390.0, 410.0, 420.0)
        assertEquals(1, catchUp.drawnMarkIndex(segments, 0))
        assertEquals(2, catchUp.drawnMarkIndex(segments, 2))
        assertEquals(3, catchUp.drawnMarkIndex(segments, 3))
        assertNull(catchUp.drawnMarkIndex(segments, 4))
        assertNull(catchUp.drawnMarkIndex(segments, 9))
    }

    @Test
    fun `a glance away doesn't count, and doesn't move an earlier mark`() {
        val catchUp = AwayCatchUp()
        catchUp.screenLeft(100.0)
        catchUp.screenReturned(400.0)
        catchUp.acknowledge()
        catchUp.screenLeft(500.0)
        catchUp.screenReturned(505.0)
        val segments = lines(150.0, 200.0, 502.0, 503.0)
        assertEquals(100.0..400.0, catchUp.away)
        assertEquals(0, catchUp.firstMissedIndex(segments))
        assertFalse(catchUp.offersJump(segments))
    }

    @Test
    fun `a new long absence replaces the mark and offers the jump again`() {
        val catchUp = AwayCatchUp()
        catchUp.screenLeft(100.0)
        catchUp.screenReturned(400.0)
        catchUp.acknowledge()
        catchUp.screenLeft(1_000.0)
        catchUp.screenReturned(2_000.0)
        val segments = lines(150.0, 200.0, 1_100.0, 1_200.0, 1_300.0)
        assertEquals(2, catchUp.firstMissedIndex(segments))
        assertTrue(catchUp.offersJump(segments))
    }

    @Test
    fun `repeated leave calls keep the moment she first left`() {
        val catchUp = AwayCatchUp()
        catchUp.screenLeft(100.0)
        catchUp.screenLeft(300.0)
        catchUp.screenReturned(320.0)
        assertEquals(100.0..320.0, catchUp.away)
    }

    @Test
    fun `a single missed line, or none, draws no mark`() {
        val catchUp = AwayCatchUp()
        catchUp.screenLeft(100.0)
        catchUp.screenReturned(400.0)
        assertNull(catchUp.firstMissedIndex(lines(50.0, 150.0, 450.0)))
        assertNull(catchUp.firstMissedIndex(emptyList()))
        assertFalse(catchUp.offersJump(lines(50.0)))
    }

    @Test
    fun `coming back without having left, and clearing, leave no mark`() {
        val catchUp = AwayCatchUp()
        catchUp.screenReturned(400.0)
        assertNull(catchUp.away)
        catchUp.screenLeft(100.0)
        catchUp.screenReturned(400.0)
        catchUp.clear()
        assertNull(catchUp.away)
        assertNull(catchUp.firstMissedIndex(lines(150.0, 200.0)))
    }

    @Test
    fun `an absence of exactly the minimum counts and one second less does not`() {
        val exact = AwayCatchUp()
        exact.screenLeft(100.0)
        exact.screenReturned(115.0)
        assertEquals(100.0..115.0, exact.away)
        val shorter = AwayCatchUp()
        shorter.screenLeft(100.0)
        shorter.screenReturned(114.0)
        assertNull(shorter.away)
    }
}
