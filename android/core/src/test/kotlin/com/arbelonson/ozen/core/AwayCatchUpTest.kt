package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.random.Random
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

    @Test
    fun `counted again only past the lines left as they were, the pipeline's lists mark what plain copies mark`() {
        val random = Random(5)
        fun line(at: Double) = TranscriptSegment(id = UUID.randomUUID(), text = "line", isCommitted = true, startTimestamp = at, lastUpdateTimestamp = at)
        val counted = AwayCatchUp()
        val plain = AwayCatchUp()
        var lines = ChunkedList.of((0 until 100).map { line(random.nextDouble(0.0, 600.0)) })
        repeat(1_000) { step ->
            if (step == 0 || step == 500) {
                for (catchUp in listOf(counted, plain)) {
                    catchUp.screenLeft(100.0 + step / 5)
                    catchUp.screenReturned(400.0 + step / 5)
                }
            }
            lines = when (if (lines.size < 2) 0 else random.nextInt(4)) {
                0 -> lines.adding(line(random.nextDouble(0.0, 600.0)))
                1 -> lines.replacing(lines.size - 1, line(random.nextDouble(0.0, 600.0)))
                2 -> lines.replacing(random.nextInt(lines.size), line(random.nextDouble(0.0, 600.0)))
                else -> random.nextInt(lines.size).let { gone -> ChunkedList.of(lines.filterIndexed { index, _ -> index != gone }) }
            }
            val copy = ArrayList(lines)
            assertEquals(plain.missedLineCount(copy), counted.missedLineCount(lines), "step $step")
            assertEquals(plain.firstMissedIndex(copy), counted.firstMissedIndex(lines), "step $step")
            assertEquals(plain.drawnMarkIndex(copy, 40), counted.drawnMarkIndex(lines, 40), "step $step")
            assertEquals(plain.offersJump(copy), counted.offersJump(lines), "step $step")
        }
    }
}
