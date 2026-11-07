package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelDownloadRetryTest {
    @Test
    fun `six retries wait as long as the captions download does, and the seventh failure gives up`() {
        assertEquals(listOf(15L, 30L, 60L, 120L, 300L, 600L), (1..6).map { ModelDownloadRetry.delaySeconds(it) })
        assertNull(ModelDownloadRetry.delaySeconds(7))
    }

    @Test
    fun `the waits are the ones the captions pipeline uses`() {
        assertEquals(AutoRecoveryPolicy().downloadDelays.map { it.toLong() }, (1..6).map { ModelDownloadRetry.delaySeconds(it) })
    }

    @Test
    fun `no failure yet, and far past the last, give nothing`() {
        assertNull(ModelDownloadRetry.delaySeconds(0))
        assertNull(ModelDownloadRetry.delaySeconds(-1))
        assertNull(ModelDownloadRetry.delaySeconds(100))
    }
}
