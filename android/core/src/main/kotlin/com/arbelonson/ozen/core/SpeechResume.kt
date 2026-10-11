package com.arbelonson.ozen.core

import java.util.UUID

class SpeechResume(private val quietSeconds: Double = 8.0) {
    private var lastTalk: Double? = null
    private var count = 0
    private var newest: Pair<UUID, String>? = null

    fun noteCaptions(lines: List<TranscriptSegment>, now: Double): Boolean {
        val latest = lines.lastOrNull()?.let { it.id to it.text }
        if (lines.size == count && latest == newest) return false
        val quiet = lastTalk?.let { now - it }
        count = lines.size
        newest = latest
        lastTalk = now
        return lines.isNotEmpty() && quiet != null && quiet > quietSeconds
    }
}
