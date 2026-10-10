package com.arbelonson.ozen.core

import kotlin.math.ceil
import kotlin.math.max

class AudioStallWatchdog private constructor(
    val isEnabled: Boolean,
    val stallTicks: Int,
) {
    private var lastChunkCount: Int? = null
    private var quietTicks = 0

    constructor(stallSeconds: Double = 6.0) : this(true, max(1, ceil(stallSeconds / TICK_SECONDS).toInt()))

    val stallSeconds: Double get() = stallTicks.toDouble() * TICK_SECONDS

    fun tick(chunksReceived: Int, systemInterrupted: Boolean): Boolean {
        if (!isEnabled) return false
        if (systemInterrupted || chunksReceived != lastChunkCount) {
            lastChunkCount = chunksReceived
            quietTicks = 0
            return false
        }
        quietTicks += 1
        return quietTicks == stallTicks
    }

    fun reset() {
        lastChunkCount = null
        quietTicks = 0
    }

    fun copy(): AudioStallWatchdog {
        val copy = AudioStallWatchdog(isEnabled, stallTicks)
        copy.lastChunkCount = lastChunkCount
        copy.quietTicks = quietTicks
        return copy
    }

    override fun equals(other: Any?): Boolean =
        other is AudioStallWatchdog && isEnabled == other.isEnabled && stallTicks == other.stallTicks &&
            lastChunkCount == other.lastChunkCount && quietTicks == other.quietTicks

    override fun hashCode(): Int {
        var result = isEnabled.hashCode()
        result = 31 * result + stallTicks
        result = 31 * result + (lastChunkCount ?: 0)
        result = 31 * result + quietTicks
        return result
    }

    companion object {
        const val TICK_SECONDS = 0.25

        val disabled: AudioStallWatchdog get() = AudioStallWatchdog(false, Int.MAX_VALUE)
    }
}
