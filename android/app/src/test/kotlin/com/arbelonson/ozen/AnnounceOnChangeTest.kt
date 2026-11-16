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
}
