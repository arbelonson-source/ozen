package com.arbelonson.ozen

import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.tr
import java.time.ZoneId

object ListeningCut {
    const val LEAST_GAP_SECONDS = 120.0
    private const val TAG = "listening"

    data class Record(val startedAt: Double, val aliveAt: Double, val version: Long)

    data class Notice(val stoppedAt: Double, val offSeconds: Double) {
        fun title(): String = tr("ההאזנה הייתה כבויה לזמן מה", "Listening was off for a while")

        fun text(zone: ZoneId = ZoneId.systemDefault()): String = tr(
            "ההאזנה נעצרה בערך ב־%1 והייתה כבויה במשך %2. בזמן הזה לא יכלו להגיע התראות.",
            "Listening stopped at about %1 and was off for %2. No alerts could come through during that time.",
            listOf(clockTime(stoppedAt, zone), ConversationStats.minutesText(offSeconds)),
        )
    }

    fun encode(record: Record): String = "$TAG ${record.startedAt} ${record.aliveAt} ${record.version}"

    fun parse(raw: String?): Record? {
        val parts = raw?.trim()?.split(' ') ?: return null
        if (parts.size != 4 || parts[0] != TAG) return null
        val started = parts[1].toDoubleOrNull()
        val alive = parts[2].toDoubleOrNull()
        val version = parts[3].toLongOrNull()
        if (started == null || alive == null || version == null) return null
        if (!started.isFinite() || !alive.isFinite() || started < 0 || alive < started) return null
        return Record(started, alive, version)
    }

    fun notice(record: Record?, now: Double, version: Long): Notice? {
        if (record == null || record.version != version) return null
        val gap = now - record.aliveAt
        if (!gap.isFinite() || gap <= LEAST_GAP_SECONDS) return null
        return Notice(record.aliveAt, gap)
    }
}
