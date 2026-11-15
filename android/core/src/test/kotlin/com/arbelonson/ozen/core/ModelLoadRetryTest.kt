package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelLoadRetryTest {
    @Test
    fun `a first load is always allowed`() {
        assertTrue(ModelLoadRetry().shouldTry(0))
    }

    @Test
    fun `a failure blocks tries for 60 seconds, then allows one`() {
        val retry = ModelLoadRetry()
        retry.failed(100_000)
        assertFalse(retry.shouldTry(100_000))
        assertFalse(retry.shouldTry(159_999))
        assertTrue(retry.shouldTry(160_000))
    }

    @Test
    fun `each further failure waits longer, then it stops after the limit`() {
        val retry = ModelLoadRetry()
        retry.failed(0)
        retry.failed(60_000)
        assertFalse(retry.shouldTry(179_999))
        assertTrue(retry.shouldTry(180_000))
        retry.failed(180_000)
        assertFalse(retry.shouldTry(419_999))
        assertTrue(retry.shouldTry(420_000))
        retry.failed(420_000)
        assertTrue(retry.gaveUp)
        assertNull(retry.nextGapSeconds)
        assertFalse(retry.shouldTry(Long.MAX_VALUE))
    }

    @Test
    fun `a success starts the count again`() {
        val retry = ModelLoadRetry()
        retry.failed(0)
        retry.failed(60_000)
        retry.succeeded()
        assertTrue(retry.shouldTry(61_000))
        retry.failed(61_000)
        assertFalse(retry.shouldTry(120_000))
        assertTrue(retry.shouldTry(121_000))
        assertEquals(60L, retry.nextGapSeconds)
    }
}
