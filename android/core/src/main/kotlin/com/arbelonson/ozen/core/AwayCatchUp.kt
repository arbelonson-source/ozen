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
    private var counted: List<TranscriptSegment> = emptyList()
    private var countedAway: ClosedFloatingPointRange<Double>? = null
    private var missed = 0
    private var firstMissed = -1

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
        counted = emptyList()
        countedAway = null
    }

    fun missedLineCount(segments: List<TranscriptSegment>): Int {
        val range = away ?: return 0
        count(segments, range)
        return missed
    }

    fun firstMissedIndex(segments: List<TranscriptSegment>): Int? {
        val range = away ?: return null
        count(segments, range)
        if (missed < max(minimumLines, 1)) return null
        return firstMissed.takeIf { it >= 0 }
    }

    private fun count(segments: List<TranscriptSegment>, range: ClosedFloatingPointRange<Double>) {
        val same = if (range == countedAway) unchangedLeadingCount(counted, segments) else 0
        if (same == 0) {
            missed = 0
            firstMissed = -1
        } else {
            for (index in same until counted.size) if (counted[index].startTimestamp in range) missed--
            if (firstMissed >= same) firstMissed = -1
        }
        for (index in same until segments.size) {
            if (segments[index].startTimestamp !in range) continue
            missed++
            if (firstMissed < 0) firstMissed = index
        }
        counted = segments
        countedAway = range
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
