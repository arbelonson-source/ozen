package com.arbelonson.ozen.core

object ConversationBreak {
    const val QUIET_SECONDS: Double = 20.0 * 60

    fun shouldStartNew(lastCaptionAt: Double?, now: Double, quietSeconds: Double = QUIET_SECONDS): Boolean {
        if (lastCaptionAt == null) return false
        return now - lastCaptionAt >= quietSeconds
    }

    fun start(listeningSince: Double?, firstLineAt: Double?, quietSeconds: Double = QUIET_SECONDS): Double? {
        if (listeningSince == null) return firstLineAt
        if (firstLineAt == null || firstLineAt - listeningSince < quietSeconds) return listeningSince
        return firstLineAt
    }
}
