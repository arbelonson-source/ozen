package com.arbelonson.ozen

internal object PhraseLines {
    private const val LARGE_TEXT_FONT_SCALE = 1.3f
    private const val ORDINARY_LINES = 2

    fun limit(fontScale: Float): Int = if (fontScale > LARGE_TEXT_FONT_SCALE) Int.MAX_VALUE else ORDINARY_LINES
}
