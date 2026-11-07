package com.arbelonson.ozen

internal class SpokenLevel(private val everyMillis: Long = 1_000) {
    private var saidAt: Long? = null
    private var loudest = 0f
    private var said = 0f

    fun heard(level: Float, nowMillis: Long): Float {
        loudest = maxOf(loudest, level)
        val last = saidAt
        if (last == null || nowMillis - last >= everyMillis) {
            said = loudest
            loudest = 0f
            saidAt = nowMillis
        }
        return said
    }
}
