package com.arbelonson.ozen.core

object TimeArticle {
    fun agreeing(text: String, time: String, language: UILanguage = Localization.language): String {
        if (hour(time) != 1) return text
        return when (language) {
            UILanguage.Spanish -> text.replace("las $time", "la $time")
            UILanguage.Portuguese -> text.replace("às $time", "à $time").replace("das $time", "da $time")
            UILanguage.Hebrew, UILanguage.English, UILanguage.Arabic, UILanguage.Russian, UILanguage.Amharic, UILanguage.French,
            UILanguage.Ukrainian, UILanguage.German, UILanguage.ChineseSimplified, UILanguage.Hindi,
            -> text
        }
    }

    internal fun hour(time: String): Int? =
        time.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()
}
