package com.arbelonson.ozen.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

class RetryWait(
    private val sinceBoot: (() -> Long)? = null,
    private val keepAwake: (Duration?) -> Unit = {},
) {
    private var due = 0
    private var resting = 0
    private val nudges = MutableStateFlow(0L)

    val nudged: Long get() = nudges.value

    fun nudge() = nudges.update { it + 1 }

    suspend fun wait(duration: Duration, keepUp: Boolean = true, nudgedSince: Long? = null): Boolean {
        if (keepUp) {
            return whileDue {
                keepAwake(duration + AFTERWARDS)
                pause(duration, nudgedSince)
            }
        }
        rest()
        try {
            return pause(duration, nudgedSince)
        } finally {
            wake(currentCoroutineContext().isActive)
        }
    }

    private suspend fun pause(duration: Duration, nudgedSince: Long?): Boolean {
        val clock = sinceBoot ?: return nudgedWithin(duration, nudgedSince)
        val end = clock() + duration.inWholeMilliseconds
        while (true) {
            val left = end - clock()
            if (left <= 0) return false
            if (nudgedWithin(minOf(left, STEP_MILLIS).milliseconds, nudgedSince)) return true
        }
    }

    private suspend fun nudgedWithin(duration: Duration, nudgedSince: Long?): Boolean {
        if (nudgedSince == null) {
            delay(duration)
            return false
        }
        if (nudges.value > nudgedSince) return true
        return withTimeoutOrNull(duration) { nudges.first { it > nudgedSince } } != null
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
        if (due == resting) keepAwake(null)
    }

    @Synchronized
    private fun rest() {
        resting += 1
        if (resting == due) keepAwake(null)
    }

    @Synchronized
    private fun wake(checking: Boolean) {
        if (checking && resting == due) keepAwake(AFTERWARDS)
        resting -= 1
    }

    companion object {
        const val STEP_MILLIS = 1_000L
        val AFTERWARDS = 2.minutes
    }
}
