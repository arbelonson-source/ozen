package com.arbelonson.ozen.core

import java.util.UUID

enum class HistoryRetention(val rawValue: String) {
    Forever("forever"),
    Year("year"),
    ThreeMonths("threeMonths"),
    Month("month"),
    Week("week");

    val days: Int?
        get() = when (this) {
            Forever -> null
            Year -> 365
            ThreeMonths -> 90
            Month -> 30
            Week -> 7
        }

    fun cutoff(now: Double): Double? = days?.let { now - it.toDouble() * 86_400 }

    fun expiringCount(conversations: List<TranscriptSessionSummary>?, now: Double): Int? {
        val cutoff = cutoff(now) ?: return 0
        return conversations?.count { it.lastActiveAt < cutoff && !it.isKeptByChoice }
    }

    companion object {
        fun fromRawValue(rawValue: String): HistoryRetention =
            entries.firstOrNull { it.rawValue == rawValue } ?: Forever
    }
}

val TranscriptSessionSummary.lastActiveAt: Double get() = endedAt ?: lastLineAt ?: startedAt

val TranscriptSessionSummary.isKeptByChoice: Boolean get() = starredCount > 0 || title != null

fun TranscriptHistoryStore.deleteConversations(inactiveBefore: Double, protecting: Set<UUID> = emptySet()): Int {
    var deleted = 0
    for (summary in listSummaries()) {
        if (summary.lastActiveAt < inactiveBefore && !summary.isKeptByChoice && summary.id !in protecting) {
            val ok = try {
                delete(summary.id)
                true
            } catch (_: Exception) {
                false
            }
            if (ok) deleted += 1
        }
    }
    return deleted
}
