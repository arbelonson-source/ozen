package com.arbelonson.ozen.core

/**
 * When to try loading a model again after a failed load: not on every
 * audio window, but after 60, 120 and 240 seconds, then stop for the
 * session. Pure, so the timing is tested without a phone.
 */
class ModelLoadRetry {
    var failureCount = 0
        private set
    private var lastFailure = 0L

    val gaveUp: Boolean get() = failureCount > GAPS_SECONDS.size

    val nextGapSeconds: Long? get() = if (failureCount == 0 || gaveUp) null else GAPS_SECONDS[failureCount - 1]

    fun shouldTry(nowMillis: Long): Boolean {
        if (failureCount == 0) return true
        val gap = nextGapSeconds ?: return false
        return nowMillis - lastFailure >= gap * 1_000
    }

    fun failed(nowMillis: Long) {
        failureCount += 1
        lastFailure = nowMillis
    }

    fun succeeded() {
        failureCount = 0
    }

    companion object {
        val GAPS_SECONDS = listOf(60L, 120L, 240L)
    }
}
