package com.arbelonson.ozen.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ContrastRatio {
    fun between(first: Triple<Double, Double, Double>, second: Triple<Double, Double, Double>): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val lighter = max(firstLuminance, secondLuminance)
        val darker = min(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun ratio(
        foreground: Triple<Double, Double, Double>,
        alpha: Double,
        overBackground: Triple<Double, Double, Double>,
    ): Double {
        val blended = Triple(
            foreground.first * alpha + overBackground.first * (1 - alpha),
            foreground.second * alpha + overBackground.second * (1 - alpha),
            foreground.third * alpha + overBackground.third * (1 - alpha),
        )
        return between(blended, overBackground)
    }

    private fun relativeLuminance(color: Triple<Double, Double, Double>): Double =
        0.2126 * linearized(color.first) + 0.7152 * linearized(color.second) + 0.0722 * linearized(color.third)

    private fun linearized(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
}
