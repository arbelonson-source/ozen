package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.math.max

object RecentConversation {
    const val clockSkewSeconds: Double = 5.0 * 60

    fun resumable(
        summaries: List<TranscriptSessionSummary>,
        now: Double,
        excluding: UUID? = null,
        window: Double = ConversationBreak.QUIET_SECONDS,
    ): TranscriptSessionSummary? =
        summaries
            .filter { summary ->
                summary.id != excluding &&
                    summary.segmentCount > 0 &&
                    summary.lastActiveAt <= now + clockSkewSeconds &&
                    now - summary.lastActiveAt < window
            }
            .fold<TranscriptSessionSummary, TranscriptSessionSummary?>(null) { best, summary ->
                if (best == null || best.lastActiveAt < summary.lastActiveAt) summary else best
            }

    fun oldestQualifyingSave(now: Double, window: Double = ConversationBreak.QUIET_SECONDS): Double =
        now - window - clockSkewSeconds

    fun minutesAgo(summary: TranscriptSessionSummary, now: Double): Int =
        max(1, ((now - summary.lastActiveAt) / 60).toInt())
}
