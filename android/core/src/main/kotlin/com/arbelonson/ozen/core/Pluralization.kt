package com.arbelonson.ozen.core

/**
 * The CLDR plural bucket a count falls into for a given language. Hebrew
 * and English keep their own dedicated logic elsewhere; this exists for
 * the other ten interface languages, each of which groups counts by its
 * own grammar rather than a single "singular or plural" split.
 */
internal enum class PluralCategory {
    Zero, One, Two, Few, Many, Other
}

/**
 * [n]'s plural category in [language], by the CLDR rule for that
 * language. Fractional counts never appear here, so [PluralCategory.Other] is
 * only reached where a language's rule has no bucket for a given `n mod 100`.
 */
internal fun pluralCategory(n: Int, language: UILanguage): PluralCategory {
    val mod10 = n % 10
    val mod100 = n % 100
    return when (language) {
        UILanguage.Hebrew, UILanguage.English -> if (n == 1) PluralCategory.One else PluralCategory.Other
        UILanguage.Russian, UILanguage.Ukrainian -> when {
            mod10 == 1 && mod100 != 11 -> PluralCategory.One
            mod10 in 2..4 && mod100 !in 12..14 -> PluralCategory.Few
            else -> PluralCategory.Many
        }
        UILanguage.Arabic -> when {
            n == 0 -> PluralCategory.Zero
            n == 1 -> PluralCategory.One
            n == 2 -> PluralCategory.Two
            mod100 in 3..10 -> PluralCategory.Few
            mod100 in 11..99 -> PluralCategory.Many
            else -> PluralCategory.Other
        }
        UILanguage.French, UILanguage.Hindi, UILanguage.Amharic ->
            if (n == 0 || n == 1) PluralCategory.One else PluralCategory.Other
        UILanguage.Spanish, UILanguage.German, UILanguage.Portuguese ->
            if (n == 1) PluralCategory.One else PluralCategory.Other
        UILanguage.ChineseSimplified -> PluralCategory.Other
    }
}

/**
 * [word], with [n] in front unless [omitNumeral] says the language
 * already carries the count in the word itself (Arabic's bare singular
 * and dual for one and two).
 */
internal fun countedPhrase(n: Int, word: String, omitNumeral: Boolean): String =
    if (omitNumeral) word else "$n $word"

/**
 * Whether [language] folds the count into the word itself for
 * [category], rather than showing a numeral (Arabic's bare "دقيقة" for
 * one, "دقيقتان" for two).
 */
internal fun omitsNumeral(category: PluralCategory, language: UILanguage): Boolean =
    language == UILanguage.Arabic && (category == PluralCategory.One || category == PluralCategory.Two)

/**
 * Minute and hour words for the ten languages with only generic plural
 * handling (no quarter/half phrasing, unlike Hebrew's `minutesText`).
 */
internal object TimeUnitWord {
    fun minute(category: PluralCategory, language: UILanguage): String = when (language) {
        UILanguage.Russian -> when (category) {
            PluralCategory.One -> "минута"
            PluralCategory.Few -> "минуты"
            else -> "минут"
        }
        UILanguage.Ukrainian -> when (category) {
            PluralCategory.One -> "хвилина"
            PluralCategory.Few -> "хвилини"
            else -> "хвилин"
        }
        UILanguage.Arabic -> when (category) {
            PluralCategory.One -> "دقيقة"
            PluralCategory.Two -> "دقيقتان"
            PluralCategory.Few -> "دقائق"
            else -> "دقيقة"
        }
        UILanguage.French -> if (category == PluralCategory.One) "minute" else "minutes"
        UILanguage.Spanish -> if (category == PluralCategory.One) "minuto" else "minutos"
        UILanguage.German -> if (category == PluralCategory.One) "Minute" else "Minuten"
        UILanguage.Portuguese -> if (category == PluralCategory.One) "minuto" else "minutos"
        UILanguage.Hindi -> "मिनट"
        UILanguage.Amharic -> "ደቂቃ"
        UILanguage.ChineseSimplified -> "分钟"
        UILanguage.Hebrew, UILanguage.English -> if (category == PluralCategory.One) "minute" else "minutes"
    }

    fun second(category: PluralCategory, language: UILanguage): String = when (language) {
        UILanguage.Russian -> when (category) {
            PluralCategory.One -> "секунда"
            PluralCategory.Few -> "секунды"
            else -> "секунд"
        }
        UILanguage.Ukrainian -> when (category) {
            PluralCategory.One -> "секунда"
            PluralCategory.Few -> "секунди"
            else -> "секунд"
        }
        UILanguage.Arabic -> when (category) {
            PluralCategory.One -> "ثانية"
            PluralCategory.Two -> "ثانيتان"
            PluralCategory.Few -> "ثوانٍ"
            else -> "ثانية"
        }
        UILanguage.French -> if (category == PluralCategory.One) "seconde" else "secondes"
        UILanguage.Spanish -> if (category == PluralCategory.One) "segundo" else "segundos"
        UILanguage.German -> if (category == PluralCategory.One) "Sekunde" else "Sekunden"
        UILanguage.Portuguese -> if (category == PluralCategory.One) "segundo" else "segundos"
        UILanguage.Hindi -> "सेकंड"
        UILanguage.Amharic -> "ሰከንድ"
        UILanguage.ChineseSimplified -> "秒"
        UILanguage.Hebrew, UILanguage.English -> if (category == PluralCategory.One) "second" else "seconds"
    }

    fun hour(category: PluralCategory, language: UILanguage): String = when (language) {
        UILanguage.Russian -> when (category) {
            PluralCategory.One -> "час"
            PluralCategory.Few -> "часа"
            else -> "часов"
        }
        UILanguage.Ukrainian -> when (category) {
            PluralCategory.One -> "година"
            PluralCategory.Few -> "години"
            else -> "годин"
        }
        UILanguage.Arabic -> when (category) {
            PluralCategory.One -> "ساعة"
            PluralCategory.Two -> "ساعتان"
            PluralCategory.Few -> "ساعات"
            else -> "ساعة"
        }
        UILanguage.French -> if (category == PluralCategory.One) "heure" else "heures"
        UILanguage.Spanish -> if (category == PluralCategory.One) "hora" else "horas"
        UILanguage.German -> if (category == PluralCategory.One) "Stunde" else "Stunden"
        UILanguage.Portuguese -> if (category == PluralCategory.One) "hora" else "horas"
        UILanguage.Hindi -> if (category == PluralCategory.One) "घंटा" else "घंटे"
        UILanguage.Amharic -> "ሰዓት"
        UILanguage.ChineseSimplified -> "小时"
        UILanguage.Hebrew, UILanguage.English -> if (category == PluralCategory.One) "hour" else "hours"
    }

    /** Rounded down to nothing worth counting ("less than a minute"). */
    fun lessThanAMinute(language: UILanguage): String = when (language) {
        UILanguage.Russian -> "меньше минуты"
        UILanguage.Ukrainian -> "менше хвилини"
        UILanguage.Arabic -> "أقل من دقيقة"
        UILanguage.French -> "moins d'une minute"
        UILanguage.Spanish -> "menos de un minuto"
        UILanguage.German -> "weniger als eine Minute"
        UILanguage.Portuguese -> "menos de um minuto"
        UILanguage.Hindi -> "एक मिनट से कम"
        UILanguage.Amharic -> "ከአንድ ደቂቃ በታች"
        UILanguage.ChineseSimplified -> "不到一分钟"
        UILanguage.Hebrew, UILanguage.English -> "less than a minute"
    }
}
