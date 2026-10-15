package com.arbelonson.ozen.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The app's own blue colour schemes, tuned by hand so every text pair clears
 * WCAG AA (4.5:1) and the high-contrast pair clears AAA. No dynamic colour.
 * Only the app chrome uses these; the caption area keeps the colours the
 * person picked (see CaptionTheme).
 */
object OzenColors {
    fun scheme(dark: Boolean, highContrast: Boolean = false): ColorScheme = when {
        dark && highContrast -> DarkHighContrast
        dark -> Dark
        highContrast -> LightHighContrast
        else -> Light
    }

    /** Green for "listening / ready" dots in StatusPill. Passes 3:1 on every surface. */
    fun statusGood(dark: Boolean): Color = if (dark) Color(0xFF6DD58C) else Color(0xFF1B7F3C)

    /** Amber for "paused / attention" dots in StatusPill. */
    fun statusWarn(dark: Boolean): Color = if (dark) Color(0xFFFFB74D) else Color(0xFF9A5B00)

    /** Amber notice card for trouble that is not an emergency: text on it passes AA. */
    fun warningContainer(dark: Boolean): Color = if (dark) Color(0xFF5A3600) else Color(0xFFFFE2C2)

    fun onWarningContainer(dark: Boolean): Color = if (dark) Color(0xFFFFE2C2) else Color(0xFF3B1F00)

    internal val Light = lightColorScheme(
        primary = Color(0xFF1A5BC4),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD9E6FF),
        onPrimaryContainer = Color(0xFF0A2A63),
        secondary = Color(0xFF475671),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDCE4F3),
        onSecondaryContainer = Color(0xFF16253F),
        tertiary = Color(0xFF1A5BC4),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD9E6FF),
        onTertiaryContainer = Color(0xFF0A2A63),
        background = Color(0xFFF8F9FC),
        onBackground = Color(0xFF191C20),
        surface = Color(0xFFF8F9FC),
        onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFE0E4EC),
        onSurfaceVariant = Color(0xFF444B57),
        surfaceTint = Color(0xFF1A5BC4),
        inverseSurface = Color(0xFF2E3136),
        inverseOnSurface = Color(0xFFEFF1F6),
        inversePrimary = Color(0xFFA9C7FF),
        outline = Color(0xFF6B7380),
        outlineVariant = Color(0xFFC4CAD4),
        scrim = Color(0xFF000000),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        surfaceBright = Color(0xFFF8F9FC),
        surfaceDim = Color(0xFFD8DBE2),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F4F8),
        surfaceContainer = Color(0xFFE4E8F0),
        surfaceContainerHigh = Color(0xFFDEE3EB),
        surfaceContainerHighest = Color(0xFFD8DDE6),
    )

    internal val Dark = darkColorScheme(
        primary = Color(0xFFA9C7FF),
        onPrimary = Color(0xFF07306F),
        primaryContainer = Color(0xFF2A4A85),
        onPrimaryContainer = Color(0xFFD9E6FF),
        secondary = Color(0xFFBEC7DC),
        onSecondary = Color(0xFF283245),
        secondaryContainer = Color(0xFF3A4458),
        onSecondaryContainer = Color(0xFFDCE4F3),
        tertiary = Color(0xFFA9C7FF),
        onTertiary = Color(0xFF07306F),
        tertiaryContainer = Color(0xFF2A4A85),
        onTertiaryContainer = Color(0xFFD9E6FF),
        background = Color(0xFF101318),
        onBackground = Color(0xFFE2E5EB),
        surface = Color(0xFF101318),
        onSurface = Color(0xFFE2E5EB),
        surfaceVariant = Color(0xFF41474F),
        onSurfaceVariant = Color(0xFFC2C8D2),
        surfaceTint = Color(0xFFA9C7FF),
        inverseSurface = Color(0xFFE2E5EB),
        inverseOnSurface = Color(0xFF2E3136),
        inversePrimary = Color(0xFF1A5BC4),
        outline = Color(0xFF8B929E),
        outlineVariant = Color(0xFF41474F),
        scrim = Color(0xFF000000),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        surfaceBright = Color(0xFF363A41),
        surfaceDim = Color(0xFF101318),
        surfaceContainerLowest = Color(0xFF0B0E12),
        surfaceContainerLow = Color(0xFF181B20),
        surfaceContainer = Color(0xFF20242B),
        surfaceContainerHigh = Color(0xFF2A2E35),
        surfaceContainerHighest = Color(0xFF353A41),
    )

    internal val LightHighContrast = Light.copy(
        primary = Color(0xFF08327A),
        primaryContainer = Color(0xFFC3D7FF),
        onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFF2B3850),
        secondaryContainer = Color(0xFFC9D4EA),
        onSecondaryContainer = Color(0xFF000000),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        onSurfaceVariant = Color(0xFF1F2530),
        outline = Color(0xFF3A414D),
        outlineVariant = Color(0xFF6B7380),
        error = Color(0xFF8C0009),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF5F6F9),
        surfaceContainer = Color(0xFFEDEFF3),
        surfaceContainerHigh = Color(0xFFE5E8ED),
        surfaceContainerHighest = Color(0xFFDDE1E7),
    )

    internal val DarkHighContrast = Dark.copy(
        primary = Color(0xFFD3E2FF),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF2B5BB5),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFDCE4F3),
        secondaryContainer = Color(0xFF4A5670),
        onSecondaryContainer = Color(0xFFFFFFFF),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        onSurfaceVariant = Color(0xFFE6EAF2),
        outline = Color(0xFFC2C8D2),
        outlineVariant = Color(0xFF8B929E),
        error = Color(0xFFFFD2CF),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF12151A),
        surfaceContainer = Color(0xFF191C21),
        surfaceContainerHigh = Color(0xFF22252B),
        surfaceContainerHighest = Color(0xFF2C3037),
    )
}
