package com.arbelonson.ozen.core

import kotlin.math.max

class AwayCatchUp(
    var minimumAwaySeconds: Double = 15.0,
    var minimumLines: Int = 2,
) {
    private var leftAt: Double? = null
    var away: ClosedFloatingPointRange<Double>? = null
        private set
    var isAcknowledged: Boolean = false
        private set

    fun screenLeft(time: Double) {
        if (leftAt == null) leftAt = time
    }

    fun screenReturned(time: Double) {
        val left = leftAt ?: return
        leftAt = null
        if (time - left < minimumAwaySeconds) return
        away = left..time
        isAcknowledged = false
    }

    fun acknowledge() {
        isAcknowledged = true
    }

    fun clear() {
        leftAt = null
        away = null
        isAcknowledged = false
    }

    fun missedLineCount(segments: List<TranscriptSegment>): Int {
        val range = away ?: return 0
        return segments.count { it.startTimestamp in range }
    }

    fun firstMissedIndex(segments: List<TranscriptSegment>): Int? {
        val range = away ?: return null
        if (missedLineCount(segments) < max(minimumLines, 1)) return null
        return segments.indexOfFirst { it.startTimestamp in range }.takeIf { it >= 0 }
    }

    fun drawnMarkIndex(segments: List<TranscriptSegment>, firstDrawnIndex: Int): Int? {
        val range = away ?: return null
        val first = firstMissedIndex(segments) ?: return null
        val index = max(first, firstDrawnIndex)
        if (index !in segments.indices || segments[index].startTimestamp !in range) return null
        return index
    }

    fun offersJump(segments: List<TranscriptSegment>): Boolean =
        !isAcknowledged && firstMissedIndex(segments) != null

    override fun equals(other: Any?): Boolean = other is AwayCatchUp &&
        minimumAwaySeconds == other.minimumAwaySeconds && minimumLines == other.minimumLines &&
        leftAt == other.leftAt && away == other.away && isAcknowledged == other.isAcknowledged

    override fun hashCode(): Int = listOf(minimumAwaySeconds, minimumLines, leftAt, away, isAcknowledged).hashCode()
}
