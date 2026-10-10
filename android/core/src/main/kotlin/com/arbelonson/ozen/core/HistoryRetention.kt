package com.arbelonson.ozen.core

import java.util.UUID

/**
 * How long saved conversations are kept before they delete themselves.
 *
 * Off ("forever") by default: nothing she said is ever thrown away unless
 * someone chose that. Conversations she marked (a starred line) or gave a
 * name are kept whatever this says; those are the ones she meant to keep.
 */
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

    /**
     * Conversations last active before this moment have expired; null when
     * nothing ever does.
     */
    fun cutoff(now: Double): Double? = days?.let { now - it.toDouble() * 86_400 }

    /**
     * How many of [conversations] (every saved one) this choice would
     * delete right away; null while that list isn't known yet, as when
     * History has only just opened, so the choice asks first instead of
     * counting nothing and deleting without a word.
     */
    fun expiringCount(conversations: List<TranscriptSessionSummary>?, now: Double): Int? {
        val cutoff = cutoff(now) ?: return 0
        return conversations?.count { it.lastActiveAt < cutoff && !it.isKeptByChoice }
    }

    companion object {
        /**
         * A settings file from a newer build may name a choice this one
         * doesn't know; keeping everything is the safe reading of that.
         */
        fun fromRawValue(rawValue: String): HistoryRetention =
            entries.firstOrNull { it.rawValue == rawValue } ?: Forever
    }
}

/**
 * When the conversation was last going: when it ended, or, for one
 * the app never got to close (a crash, the system ending it in the
 * background), when its newest line began.
 */
val TranscriptSessionSummary.lastActiveAt: Double get() = endedAt ?: lastLineAt ?: startedAt

/** Starred lines or a name mean she wanted this one kept. */
val TranscriptSessionSummary.isKeptByChoice: Boolean get() = starredCount > 0 || title != null

/**
 * Deletes conversations last active before [inactiveBefore], except ones
 * kept by choice (see `isKeptByChoice`) and the ones in [protecting] (the
 * conversation still on screen). Returns how many were deleted.
 */
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
