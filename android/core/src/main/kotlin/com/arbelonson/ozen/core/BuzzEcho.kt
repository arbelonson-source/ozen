package com.arbelonson.ozen.core

object BuzzEcho {
    const val TAIL_SECONDS: Double = 0.4

    fun ignores(pattern: AlertVibration, startedAt: Double, at: Double): Boolean {
        val into = at - startedAt
        return pattern.pulses.any { into >= it.start && into < it.end + TAIL_SECONDS }
    }
}
