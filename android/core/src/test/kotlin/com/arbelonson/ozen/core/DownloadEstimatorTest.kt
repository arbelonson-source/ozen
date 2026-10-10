package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadEstimatorTest {
    @Test
    fun `a steady download is estimated from its pace`() {
        val estimator = DownloadEstimator()
        // 1% a second.
        for (second in 0..10) {
            estimator.record(fraction = second / 100.0, time = 1_000.0 + second)
        }
        val left = assertNotNull(estimator.secondsRemaining())
        assertTrue(abs(left - 90) < 0.5)
    }

    @Test
    fun `no estimate in the first seconds, or while nothing moves`() {
        val estimator = DownloadEstimator()
        assertNull(estimator.secondsRemaining())
        estimator.record(fraction = 0.1, time = 0.0)
        estimator.record(fraction = 0.12, time = 3.0)
        assertNull(estimator.secondsRemaining())

        val stalled = DownloadEstimator()
        stalled.record(fraction = 0.4, time = 0.0)
        stalled.record(fraction = 0.4, time = 20.0)
        assertNull(stalled.secondsRemaining())
    }

    @Test
    fun `the estimate follows the recent pace, not the average since the start`() {
        val estimator = DownloadEstimator()
        // Two minutes at 0.1% a second on a poor connection...
        for (second in 0..120 step 5) {
            estimator.record(fraction = second / 1_000.0, time = second.toDouble())
        }
        // ...then half a minute at 1% a second on better Wi-Fi.
        for (second in 125..160 step 5) {
            estimator.record(fraction = 0.12 + (second - 120) / 100.0, time = second.toDouble())
        }
        val left = assertNotNull(estimator.secondsRemaining())
        // 48% left at 1%/s is 48 s; the whole-download average would say several minutes.
        assertTrue(left < 60)
        assertTrue(left > 40)
    }

    @Test
    fun `a stall longer than the window doesn't leave the estimate anchored to pre-stall progress`() {
        val estimator = DownloadEstimator()
        estimator.record(fraction = 0.1, time = 0.0)
        // A ten-minute stall -- no progress reported at all.
        estimator.record(fraction = 0.11, time = 600.0)
        estimator.record(fraction = 0.5, time = 605.0)
        val left = assertNotNull(estimator.secondsRemaining())
        // True recent pace is (0.5-0.11)/5, about 7.8%/s, about 6.4s left.
        // Anchored to the stale sample from before the stall instead, the
        // span balloons to 605s and the estimate to well over ten minutes.
        assertTrue(left < 15)
    }

    @Test
    fun `a resumed download's first reading is where it picked up, not how fast it goes`() {
        val estimator = DownloadEstimator()
        // Announced at 0%, then the part already on the phone: 57%.
        estimator.record(fraction = 0.0, time = 0.0)
        estimator.record(fraction = 0.57, time = 1.0)
        // Then 0.1% a second.
        for (second in 2..6) {
            estimator.record(fraction = 0.57 + (second - 1) / 1_000.0, time = second.toDouble())
        }
        val left = assertNotNull(estimator.secondsRemaining())
        assertTrue(abs(left - 425) < 5)
    }

    @Test
    fun `silence counts as news only when it is much longer than the usual gap between reports`() {
        val estimator = DownloadEstimator()
        assertEquals(30.0, estimator.quietLimit(floor = 30.0, gaps = 3.0))
        for (second in 0..60 step 20) {
            estimator.record(fraction = second / 1_000.0, time = second.toDouble())
        }
        assertEquals(60.0, estimator.quietLimit(floor = 30.0, gaps = 3.0))
        assertEquals(90.0, estimator.quietLimit(floor = 90.0, gaps = 3.0))

        val quick = DownloadEstimator()
        for (second in 0..10) {
            quick.record(fraction = second / 100.0, time = second.toDouble())
        }
        assertEquals(30.0, quick.quietLimit(floor = 30.0, gaps = 3.0))
    }

    @Test
    fun `a download that starts over starts the estimate over`() {
        val estimator = DownloadEstimator()
        estimator.record(fraction = 0.5, time = 0.0)
        estimator.record(fraction = 0.6, time = 10.0)
        estimator.record(fraction = 0.0, time = 11.0)
        estimator.record(fraction = 0.01, time = 13.0)
        assertNull(estimator.secondsRemaining())
        // The new download's own pace, not the old one's higher readings.
        estimator.record(fraction = 0.11, time = 23.0)
        val left = assertNotNull(estimator.secondsRemaining())
        assertTrue(abs(left - 89) < 0.5)
    }

    @Test
    fun `reset forgets the pace, so a new download is timed afresh`() {
        val estimator = DownloadEstimator()
        estimator.record(fraction = 0.1, time = 0.0)
        estimator.record(fraction = 0.2, time = 10.0)
        assertNotNull(estimator.secondsRemaining())
        estimator.reset()
        assertNull(estimator.secondsRemaining())
    }

    @Test
    fun `two estimators with the same readings are equal`() {
        val a = DownloadEstimator()
        val b = DownloadEstimator()
        assertEquals(a, b)
        a.record(fraction = 0.1, time = 0.0)
        assertTrue(a != b)
        b.record(fraction = 0.1, time = 0.0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
