package com.arbelonson.ozen.core

import kotlin.math.ceil
import kotlin.math.max

class AudioStallWatchdog private constructor(
    val isEnabled: Boolean,
    val stallTicks: Int,
    private val silenceSamples: Long = Long.MAX_VALUE,
) {
    private var lastChunkCount: Int? = null
    private var quietTicks = 0
    private var silentSamples = 0L

    val silenceSeconds: Double
        get() = if (silenceSamples == Long.MAX_VALUE) Double.MAX_VALUE else silenceSamples.toDouble() / SAMPLE_RATE

    fun heard(chunk: FloatArray, systemInterrupted: Boolean) {
        if (!isEnabled) return
        if (systemInterrupted) {
            silentSamples = 0
            return
        }
        if (chunk.any { it != 0f }) {
            silentSamples = 0
            return
        }
        silentSamples += chunk.size
    }

    val isSilent: Boolean get() = isEnabled && silentSamples >= silenceSamples

    constructor(stallSeconds: Double = 6.0, silenceSeconds: Double = 8.0) : this(
        true,
        max(1, ceil(stallSeconds / TICK_SECONDS).toInt()),
        max(1L, (silenceSeconds * SAMPLE_RATE).toLong()),
    )

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
        silentSamples = 0
    }

    fun copy(): AudioStallWatchdog {
        val copy = AudioStallWatchdog(isEnabled, stallTicks, silenceSamples)
        copy.lastChunkCount = lastChunkCount
        copy.quietTicks = quietTicks
        copy.silentSamples = silentSamples
        return copy
    }

    override fun equals(other: Any?): Boolean =
        other is AudioStallWatchdog && isEnabled == other.isEnabled && stallTicks == other.stallTicks &&
            silenceSamples == other.silenceSamples && lastChunkCount == other.lastChunkCount &&
            quietTicks == other.quietTicks && silentSamples == other.silentSamples

    override fun hashCode(): Int {
        var result = isEnabled.hashCode()
        result = 31 * result + stallTicks
        result = 31 * result + (lastChunkCount ?: 0)
        result = 31 * result + quietTicks
        result = 31 * result + silenceSamples.hashCode()
        result = 31 * result + silentSamples.hashCode()
        return result
    }

    companion object {
        const val TICK_SECONDS = 0.25
        private const val SAMPLE_RATE = 16_000.0

        val disabled: AudioStallWatchdog get() = AudioStallWatchdog(false, Int.MAX_VALUE)
    }
}
