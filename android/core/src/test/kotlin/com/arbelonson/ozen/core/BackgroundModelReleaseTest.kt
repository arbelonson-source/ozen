package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundModelReleaseTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun `a phone with 4 GiB or less, or an unknown amount, gives the model back after two minutes`() {
        assertEquals(120, BackgroundModelRelease.delaySeconds(2 * gib))
        assertEquals(120, BackgroundModelRelease.delaySeconds(3 * gib))
        assertEquals(120, BackgroundModelRelease.delaySeconds(4 * gib))
        assertEquals(120, BackgroundModelRelease.delaySeconds(null))
    }

    @Test
    fun `a phone with more than 4 GiB waits ten minutes`() {
        assertEquals(600, BackgroundModelRelease.delaySeconds(4 * gib + 1))
        assertEquals(600, BackgroundModelRelease.delaySeconds(6 * gib))
    }

    @Test
    fun `the model is released only when nothing runs, nothing is about to load and the service is gone`() {
        assertTrue(BackgroundModelRelease.shouldRelease(listening = false, transitioning = false, mayLoadModels = false, serviceRunning = false))
    }

    @Test
    fun `the model is kept while any one thing still needs it`() {
        assertFalse(BackgroundModelRelease.shouldRelease(listening = true, transitioning = false, mayLoadModels = false, serviceRunning = false))
        assertFalse(BackgroundModelRelease.shouldRelease(listening = false, transitioning = true, mayLoadModels = false, serviceRunning = false))
        assertFalse(BackgroundModelRelease.shouldRelease(listening = false, transitioning = false, mayLoadModels = true, serviceRunning = false))
        assertFalse(BackgroundModelRelease.shouldRelease(listening = false, transitioning = false, mayLoadModels = false, serviceRunning = true))
    }
}
