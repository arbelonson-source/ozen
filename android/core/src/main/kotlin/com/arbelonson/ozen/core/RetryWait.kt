package com.arbelonson.ozen.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay

class RetryWait(
    private val sinceBoot: (() -> Long)? = null,
    private val keepAwake: (Duration?) -> Unit = {},
) {
    private var due = 0

    suspend fun wait(duration: Duration) {
        whileDue {
            keepAwake(duration + AFTERWARDS)
            val clock = sinceBoot
            if (clock == null) {
                delay(duration)
            } else {
                val end = clock() + duration.inWholeMilliseconds
                while (true) {
                    val left = end - clock()
                    if (left <= 0) break
                    delay(minOf(left, STEP_MILLIS))
                }
            }
        }
    }

    inline fun <R> whileDue(block: () -> R): R {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }

    @PublishedApi
    @Synchronized
    internal fun begin() {
        due += 1
    }

    @PublishedApi
    @Synchronized
    internal fun end() {
        due -= 1
        if (due == 0) keepAwake(null)
    }

    companion object {
        const val STEP_MILLIS = 1_000L
        val AFTERWARDS = 2.minutes
    }
}
