package com.arbelonson.ozen.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OzenColorsTest {
    private val variants = listOf(
        "light" to OzenColors.scheme(dark = false),
        "dark" to OzenColors.scheme(dark = true),
        "light high contrast" to OzenColors.scheme(dark = false, highContrast = true),
        "dark high contrast" to OzenColors.scheme(dark = true, highContrast = true),
    )

    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).coerceAtLeast(luminance(b)) to luminance(a).coerceAtMost(luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun ColorScheme.textPairs(): Map<String, Pair<Color, Color>> {
        val grounds = listOf(
            "surface" to surface, "surfaceContainerLowest" to surfaceContainerLowest, "surfaceContainerLow" to surfaceContainerLow,
            "surfaceContainer" to surfaceContainer, "surfaceContainerHigh" to surfaceContainerHigh, "surfaceContainerHighest" to surfaceContainerHighest,
        )
        val pairs = mutableMapOf(
            "onPrimary/primary" to (onPrimary to primary),
            "onPrimaryContainer/primaryContainer" to (onPrimaryContainer to primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (onSecondaryContainer to secondaryContainer),
            "onError/error" to (onError to error),
            "onErrorContainer/errorContainer" to (onErrorContainer to errorContainer),
            "primary/surface" to (primary to surface),
            "error/surface" to (error to surface),
        )
        for ((name, ground) in grounds) {
            pairs["onSurface/$name"] = onSurface to ground
            pairs["onSurfaceVariant/$name"] = onSurfaceVariant to ground
        }
        return pairs
    }

    @Test
    fun everyTextPairInEveryVariantPassesAA() {
        for ((variant, scheme) in variants) for ((pair, colors) in scheme.textPairs()) {
            val ratio = contrast(colors.first, colors.second)
            assertTrue(ratio >= 4.5, "$variant $pair is only %.2f:1".format(ratio))
        }
    }

    @Test
    fun highContrastVariantsAreStrongerThanTheNormalOnes() {
        assertTrue(contrast(OzenColors.scheme(false, true).onSurfaceVariant, OzenColors.scheme(false, true).surfaceContainer) >= 7.0)
        assertTrue(contrast(OzenColors.scheme(true, true).onSurfaceVariant, OzenColors.scheme(true, true).surfaceContainer) >= 7.0)
        assertTrue(contrast(OzenColors.scheme(false, true).onSurface, OzenColors.scheme(false, true).surface) >= 20.0)
        assertTrue(contrast(OzenColors.scheme(true, true).onSurface, OzenColors.scheme(true, true).surface) >= 20.0)
    }

    @Test
    fun askingForMoreContrastPicksTheHighContrastScheme() {
        assertEquals(OzenColors.LightHighContrast, OzenColors.scheme(dark = false, highContrast = true))
        assertEquals(OzenColors.DarkHighContrast, OzenColors.scheme(dark = true, highContrast = true))
        assertEquals(OzenColors.Light, OzenColors.scheme(dark = false))
        assertEquals(OzenColors.Dark, OzenColors.scheme(dark = true))
        assertNotEquals(OzenColors.Light.onSurfaceVariant, OzenColors.LightHighContrast.onSurfaceVariant)
    }

    @Test
    fun accentIsBlueNotTheBaselinePurple() {
        for ((variant, scheme) in variants) {
            val argb = scheme.primary.toArgb()
            val red = (argb shr 16) and 0xFF
            val blue = argb and 0xFF
            assertTrue(blue > red + 40, "$variant primary must be blue")
            assertNotEquals(0xFF6750A4.toInt(), argb, variant)
            assertNotEquals(0xFFD0BCFF.toInt(), argb, variant)
        }
    }

    @Test
    fun surfaceContainersStepApartSoGroupsAreVisible() {
        for ((variant, scheme) in variants) {
            val levels = listOf(scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest).map(::luminance)
            assertTrue(levels.zipWithNext().all { (a, b) -> a != b }, "$variant containers must be distinct")
        }
    }

    @Test
    fun statusDotsStandOutFromEverySurface() {
        for (dark in listOf(false, true)) {
            val ground = OzenColors.scheme(dark).surfaceContainerHigh
            assertTrue(contrast(OzenColors.statusGood(dark), ground) >= 3.0)
            assertTrue(contrast(OzenColors.statusWarn(dark), ground) >= 3.0)
        }
    }
}
