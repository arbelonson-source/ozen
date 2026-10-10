package com.arbelonson.ozen.core

object TextDirection {
    const val RIGHT_TO_LEFT_MARK = "‏"
    const val LEFT_TO_RIGHT_MARK = "‎"

    fun opensLeftToRight(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isAlphabetic(codePoint)) return !isRightToLeftLetter(codePoint)
            index += Character.charCount(codePoint)
        }
        return false
    }

    fun isRightToLeft(languageCode: String): Boolean =
        languageCode.split("-").first().lowercase() in setOf("he", "iw", "yi", "ar", "fa", "ur")

    internal fun isRightToLeftLetter(codePoint: Int): Boolean =
        codePoint in 0x0590..0x08FF || codePoint in 0xFB1D..0xFDFF || codePoint in 0xFE70..0xFEFF
}
