package com.arbelonson.ozen.core

object KeywordPill {
    const val BASE_SECONDS = 5.0
    const val EXTRA_SECONDS_PER_CHARACTER = 0.1
    const val MAX_SECONDS = 20.0

    fun visibleSeconds(characterCount: Int, accessibilitySize: Boolean): Double {
        if (!accessibilitySize) return BASE_SECONDS
        return minOf(BASE_SECONDS + maxOf(characterCount, 0) * EXTRA_SECONDS_PER_CHARACTER, MAX_SECONDS)
    }

    fun visibleSeconds(text: String, accessibilitySize: Boolean): Double =
        visibleSeconds(text.codePointCount(0, text.length), accessibilitySize)

    fun secondsLeft(total: Double, elapsed: Double): Double = minOf(total, maxOf(0.0, total - elapsed))

    fun text(said: String, speaker: String?, language: UILanguage = Localization.language): String =
        speaker?.let { tr("%1 — נאמר: %2", "%1 said: %2", listOf(it, said), language) }
            ?: tr("נאמר: %1", "Said: %1", listOf(said), language)
}
