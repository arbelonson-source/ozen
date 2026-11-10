package com.arbelonson.ozen.core

import java.math.BigDecimal
import java.math.RoundingMode

object NumberText {
    fun decimal(value: Double, places: Int, language: UILanguage = Localization.language): String {
        val text = if (value.isFinite()) BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toPlainString() else value.toString()
        return if (usesDecimalComma(language)) text.replace('.', ',') else text
    }

    internal fun usesDecimalComma(language: UILanguage): Boolean = when (language) {
        UILanguage.French, UILanguage.German, UILanguage.Russian, UILanguage.Ukrainian, UILanguage.Spanish, UILanguage.Portuguese -> true
        UILanguage.Hebrew, UILanguage.English, UILanguage.Arabic, UILanguage.Hindi, UILanguage.Amharic, UILanguage.ChineseSimplified -> false
    }
}
