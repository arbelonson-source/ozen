package com.arbelonson.ozen

import androidx.compose.ui.graphics.Color
import com.arbelonson.ozen.core.DisplayPreferences.Theme
import com.arbelonson.ozen.core.tr

data class CaptionTheme(
    val background: Color,
    val text: Color,
    val pendingText: Color,
    val numberText: Color,
    val chrome: Color,
    val isLight: Boolean,
) {
    companion object {
        fun of(theme: Theme, systemDark: Boolean): CaptionTheme = when (theme) {
            Theme.MatchPhone -> of(if (systemDark) Theme.Dark else Theme.Light, systemDark)
            Theme.Dark -> CaptionTheme(
                background = Color.Black,
                text = Color.White,
                pendingText = Color.White.copy(alpha = 0.78f),
                numberText = NUMBER_YELLOW,
                chrome = Color.White,
                isLight = false,
            )
            Theme.HighContrast -> CaptionTheme(
                background = Color.Black,
                text = NUMBER_YELLOW,
                pendingText = NUMBER_YELLOW.copy(alpha = 0.78f),
                numberText = Color.White,
                chrome = NUMBER_YELLOW,
                isLight = false,
            )
            Theme.Light -> CaptionTheme(
                background = Color.White,
                text = Color.Black,
                pendingText = Color.Black.copy(alpha = 0.7f),
                numberText = Color(red = 0f, green = 0.25f, blue = 0.7f),
                chrome = Color.Black,
                isLight = true,
            )
        }

        fun name(theme: Theme): String = when (theme) {
            Theme.Dark -> tr("לבן על שחור", "White on black")
            Theme.HighContrast -> tr("צהוב על שחור", "Yellow on black")
            Theme.Light -> tr("שחור על לבן", "Black on white")
            Theme.MatchPhone -> tr("כמו בטלפון (בהיר או כהה)", "Match the phone (light or dark)")
        }
    }
}
