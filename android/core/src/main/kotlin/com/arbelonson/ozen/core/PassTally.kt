package com.arbelonson.ozen.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

class PassTally {
    var livePasses = 0
        private set
    var finalPasses = 0
        private set
    var liveSeconds = 0.0
        private set
    var slowestLiveSeconds = 0.0
        private set
    var lastLiveSeconds: Double? = null
        private set
    var countedPasses = 0
        private set
    var countedSeconds = 0.0
        private set
    var reportedPasses = 0
        private set
    var lowPowerPasses = 0
        private set
    var lowPowerSeconds = 0.0
        private set
    var reportedLowPowerPasses = 0
        private set
    var segmentsSeen = 0
        private set
    var segmentsRejected = 0
        private set
    var emptyFinalPasses = 0
        private set
    var skippedWithoutVoice = 0
        private set

    fun recordLivePass(seconds: Double) {
        livePasses += 1
        liveSeconds += seconds
        slowestLiveSeconds = max(slowestLiveSeconds, seconds)
        lastLiveSeconds = seconds
    }

    fun recordCountedPass(seconds: Double) {
        countedPasses += 1
        countedSeconds += seconds
    }

    fun markReported() {
        reportedPasses = countedPasses
    }

    fun recordLowPowerPass(seconds: Double) {
        lowPowerPasses += 1
        lowPowerSeconds += seconds
    }

    fun markLowPowerReported() {
        reportedLowPowerPasses = lowPowerPasses
    }

    fun recordFinalPass(cameBackEmpty: Boolean) {
        finalPasses += 1
        if (cameBackEmpty) emptyFinalPasses += 1
    }

    fun recordSkippedWithoutVoice() {
        skippedWithoutVoice += 1
    }

    fun recordSegments(seen: Int, accepted: Int) {
        segmentsSeen += seen
        segmentsRejected += max(0, seen - accepted)
    }

    val summary: String
        get() {
            val average = if (livePasses > 0) twoDecimals(liveSeconds / livePasses.toDouble()) else "-"
            val last = lastLiveSeconds?.let { twoDecimals(it) } ?: "-"
            return "live passes $livePasses avg ${average}s slowest ${twoDecimals(slowestLiveSeconds)}s last ${last}s, " +
                "final passes $finalPasses ($emptyFinalPasses empty), " +
                "segments $segmentsSeen rejected by the filter $segmentsRejected, skipped with no voice $skippedWithoutVoice"
        }

    fun copy(): PassTally {
        val copy = PassTally()
        copy.livePasses = livePasses
        copy.finalPasses = finalPasses
        copy.liveSeconds = liveSeconds
        copy.slowestLiveSeconds = slowestLiveSeconds
        copy.lastLiveSeconds = lastLiveSeconds
        copy.countedPasses = countedPasses
        copy.countedSeconds = countedSeconds
        copy.reportedPasses = reportedPasses
        copy.lowPowerPasses = lowPowerPasses
        copy.lowPowerSeconds = lowPowerSeconds
        copy.reportedLowPowerPasses = reportedLowPowerPasses
        copy.segmentsSeen = segmentsSeen
        copy.segmentsRejected = segmentsRejected
        copy.emptyFinalPasses = emptyFinalPasses
        copy.skippedWithoutVoice = skippedWithoutVoice
        return copy
    }

    override fun equals(other: Any?): Boolean =
        other is PassTally && livePasses == other.livePasses && finalPasses == other.finalPasses &&
            liveSeconds == other.liveSeconds && slowestLiveSeconds == other.slowestLiveSeconds &&
            lastLiveSeconds == other.lastLiveSeconds && countedPasses == other.countedPasses &&
            countedSeconds == other.countedSeconds && reportedPasses == other.reportedPasses && segmentsSeen == other.segmentsSeen &&
            segmentsRejected == other.segmentsRejected && emptyFinalPasses == other.emptyFinalPasses &&
            skippedWithoutVoice == other.skippedWithoutVoice && lowPowerPasses == other.lowPowerPasses &&
            lowPowerSeconds == other.lowPowerSeconds && reportedLowPowerPasses == other.reportedLowPowerPasses

    override fun hashCode(): Int = listOf(
        livePasses, finalPasses, liveSeconds, slowestLiveSeconds, lastLiveSeconds, countedPasses, countedSeconds, reportedPasses,
        segmentsSeen, segmentsRejected, emptyFinalPasses, skippedWithoutVoice, lowPowerPasses, lowPowerSeconds, reportedLowPowerPasses,
    ).hashCode()

    private fun twoDecimals(value: Double): String =
        BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
}
