package com.arbelonson.ozen

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
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
        fun of(theme: Theme, systemDark: Boolean, moreContrast: Boolean = false): CaptionTheme =
            chosen(theme, systemDark).let { if (moreContrast) it.copy(pendingText = it.text) else it }

        private fun chosen(theme: Theme, systemDark: Boolean): CaptionTheme = when (theme) {
            Theme.MatchPhone -> chosen(if (systemDark) Theme.Dark else Theme.Light, systemDark)
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

@Composable
fun phoneAsksForMoreContrast(): Boolean {
    val context = LocalContext.current
    var asks by remember { mutableStateOf(asksForMoreContrast(context)) }
    LifecycleResumeEffect(Unit) {
        asks = asksForMoreContrast(context)
        onPauseOrDispose {}
    }
    return asks
}

private fun asksForMoreContrast(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && context.getSystemService(UiModeManager::class.java).contrast > 0f
