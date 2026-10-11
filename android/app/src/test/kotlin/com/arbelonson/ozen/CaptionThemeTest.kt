package com.arbelonson.ozen

import androidx.compose.ui.graphics.Color
import com.arbelonson.ozen.core.ContrastRatio
import com.arbelonson.ozen.core.DisplayPreferences.Theme
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionThemeTest {
    @Test
    fun `white on black has yellow numbers`() {
        val theme = CaptionTheme.of(Theme.Dark, systemDark = false)
        assertEquals(Color.Black, theme.background)
        assertEquals(Color.White, theme.text)
        assertEquals(NUMBER_YELLOW, theme.numberText)
        assertFalse(theme.isLight)
    }

    @Test
    fun `yellow on black has white numbers, the one colour brighter than the yellow`() {
        val theme = CaptionTheme.of(Theme.HighContrast, systemDark = false)
        assertEquals(Color.Black, theme.background)
        assertEquals(NUMBER_YELLOW, theme.text)
        assertEquals(Color.White, theme.numberText)
    }

    @Test
    fun `black on white has deep blue numbers and a light screen`() {
        val theme = CaptionTheme.of(Theme.Light, systemDark = true)
        assertEquals(Color.White, theme.background)
        assertEquals(Color.Black, theme.text)
        assertEquals(Color(red = 0f, green = 0.25f, blue = 0.7f), theme.numberText)
        assertTrue(theme.isLight)
    }

    @Test
    fun `matching the phone follows its light or dark setting`() {
        assertEquals(CaptionTheme.of(Theme.Dark, systemDark = true), CaptionTheme.of(Theme.MatchPhone, systemDark = true))
        assertEquals(CaptionTheme.of(Theme.Light, systemDark = false), CaptionTheme.of(Theme.MatchPhone, systemDark = false))
    }

    @Test
    fun `words still being heard are dimmer than finished ones`() {
        Theme.entries.forEach { choice ->
            val theme = CaptionTheme.of(choice, systemDark = true)
            assertTrue(theme.pendingText.alpha < theme.text.alpha, "$choice")
        }
    }

    @Test
    fun `every colour keeps 7 to 1 against its background, the dimmer words still being heard too`() {
        for (systemDark in listOf(true, false)) {
            Theme.entries.forEach { choice ->
                val theme = CaptionTheme.of(choice, systemDark)
                listOf(theme.text, theme.pendingText, theme.numberText, theme.chrome).forEach { colour ->
                    val ratio = ContrastRatio.ratio(colour.rgb(), colour.alpha.toDouble(), theme.background.rgb())
                    assertTrue(ratio >= 7.0, "$choice $colour: $ratio")
                }
            }
        }
    }

    @Test
    fun `each choice has the iPhone's name`() = Localization.withLanguage(UILanguage.English) {
        assertEquals(
            listOf("White on black", "Yellow on black", "Black on white", "Match the phone (light or dark)"),
            Theme.entries.map(CaptionTheme::name),
        )
    }

    private fun Color.rgb() = Triple(red.toDouble(), green.toDouble(), blue.toDouble())
}
