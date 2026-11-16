package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnounceOnChangeTest {
    @Test
    fun `a notice already showing when the screen opens is not announced`() {
        val tracker = AnnounceOnChange()
        assertFalse(tracker.observe(true))
        assertFalse(tracker.observe(true))
    }

    @Test
    fun `a notice that appears while the screen is open is announced once`() {
        val tracker = AnnounceOnChange()
        assertFalse(tracker.observe(false))
        assertTrue(tracker.observe(true))
        assertFalse(tracker.observe(true))
    }

    @Test
    fun `a notice that goes and comes back is announced again, and going is never announced`() {
        val tracker = AnnounceOnChange()
        tracker.observe(true)
        assertFalse(tracker.observe(false))
        assertTrue(tracker.observe(true))
    }

    @Test
    fun `a notice whose words change while it is showing is announced again`() {
        val tracker = AnnounceOnChange()
        assertFalse(tracker.observe(true, "one"))
        assertFalse(tracker.observe(true, "one"))
        assertTrue(tracker.observe(true, "two"))
        assertFalse(tracker.observe(true, "two"))
    }

    @Test
    fun `words that change while the notice is hidden are not announced`() {
        val tracker = AnnounceOnChange()
        assertFalse(tracker.observe(false, "one"))
        assertFalse(tracker.observe(false, "two"))
        assertTrue(tracker.observe(true, "two"))
    }
}
