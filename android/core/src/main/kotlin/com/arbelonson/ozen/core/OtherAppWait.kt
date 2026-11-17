package com.arbelonson.ozen.core

internal class OtherAppWait(
    private val retrySeconds: List<Double> = RETRY_SECONDS,
    private val holdSeconds: Double = HOLD_SECONDS,
) {
    var attempts = 0
        private set

    var waiting = false
        private set

    private var resumedAt: Double? = null

    fun interrupted(now: Double): Boolean {
        val silencedAgain = resumedAt?.let { now - it < holdSeconds } == true
        attempts = if (silencedAgain) attempts + 1 else 0
        resumedAt = null
        val begins = !waiting
        waiting = true
        return begins
    }

    fun nextDelaySeconds(): Double = retrySeconds[minOf(attempts, retrySeconds.lastIndex)]

    fun resumed(now: Double) {
        resumedAt = now
    }

    fun held(now: Double): Boolean {
        val since = resumedAt ?: return false
        if (!waiting || now - since < holdSeconds) return false
        attempts = 0
        waiting = false
        return true
    }

    fun reset() {
        attempts = 0
        waiting = false
        resumedAt = null
    }

    companion object {
        val RETRY_SECONDS = listOf(0.0, 3.0, 10.0, 30.0)
        const val HOLD_SECONDS = 5.0
    }
}
