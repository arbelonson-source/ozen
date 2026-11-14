package com.arbelonson.ozen.core

internal object CloudTime {
    const val LONGEST_MS = 86_400_000

    fun milliseconds(ms: Double): Int? {
        if (!ms.isFinite() || ms < 0 || ms > LONGEST_MS) return null
        return Math.floor(ms + 0.5).toInt()
    }

    fun milliseconds(ms: Int?): Int? = ms?.takeIf { it in 0..LONGEST_MS }

    fun span(start: Int, end: Int?): Long = if (end == null) 0L else end.toLong() - start.toLong()
}
