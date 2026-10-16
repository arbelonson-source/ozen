package com.arbelonson.ozen.core

object TranscriptionProgress {
    private const val LEAST_ELAPSED_SECONDS = 5.0
    private const val LEAST_DONE_SECONDS = 3.0

    fun fraction(doneSeconds: Double, totalSeconds: Double?): Float? {
        if (totalSeconds == null || !(totalSeconds > 0)) return null
        return (doneSeconds / totalSeconds).coerceIn(0.0, 1.0).toFloat()
    }

    fun percent(fraction: Float): Int = Math.floor(fraction * 100.0 + 1e-4).toInt().coerceIn(0, 100)

    fun secondsLeft(elapsedSeconds: Double, doneSeconds: Double, totalSeconds: Double?): Double? {
        if (totalSeconds == null || !(totalSeconds > 0)) return null
        if (doneSeconds >= totalSeconds) return 0.0
        if (elapsedSeconds < LEAST_ELAPSED_SECONDS || doneSeconds < LEAST_DONE_SECONDS) return null
        return elapsedSeconds * (totalSeconds - doneSeconds) / doneSeconds
    }

    fun timeLeftText(seconds: Double): String {
        if (seconds < 60) return tr("נותרה פחות מדקה", "Less than a minute left")
        val minutes = Math.round(seconds / 60).toInt()
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> tr("נותרו בערך %1 דק׳", "About %1 min left", listOf("$minutes"))
            rest == 0 -> tr("נותרו בערך %1 ש׳", "About %1 h left", listOf("$hours"))
            else -> tr("נותרו בערך %1 ש׳ ו-%2 דק׳", "About %1 h %2 min left", listOf("$hours", "$rest"))
        }
    }
}
