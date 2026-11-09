package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class KeptAwakeTest {
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

    @Test
    fun `each wait takes the lock for as long as it asks, renewing it, and letting go releases it once`() {
        awake.keepAwake(15.seconds + 2.minutes)
        awake.keepAwake(30.seconds + 2.minutes)
        assertTrue(awake.held)
        awake.keepAwake(null)
        awake.keepAwake(null)
        assertFalse(awake.held)
        assertEquals(listOf("acquire 135000", "acquire 150000", "release"), calls)
    }

    @Test
    fun `letting go with nothing held releases nothing`() {
        awake.keepAwake(null)
        assertTrue(calls.isEmpty())
        assertFalse(awake.held)
    }
}
