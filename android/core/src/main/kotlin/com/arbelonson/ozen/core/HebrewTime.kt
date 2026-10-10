package com.arbelonson.ozen.core

object HebrewTime {
    fun minutesAgo(minutes: Int): String {
        when (val language = Localization.language) {
            UILanguage.English -> return englishMinutesAgo(minutes)
            UILanguage.Hebrew -> Unit
            else -> return genericMinutesAgo(minutes, language)
        }
        return when {
            minutes <= 1 -> "לפני דקה"
            minutes == 2 -> "לפני שתי דקות"
            minutes < 60 -> "לפני $minutes דקות"
            minutes < 120 -> "לפני שעה"
            minutes < 180 -> "לפני שעתיים"
            else -> "לפני ${minutes / 60} שעות"
        }
    }

    private fun englishMinutesAgo(minutes: Int): String = when {
        minutes <= 1 -> "a minute ago"
        minutes < 60 -> "$minutes minutes ago"
        minutes < 120 -> "an hour ago"
        else -> "${minutes / 60} hours ago"
    }

    private fun genericMinutesAgo(minutes: Int, language: UILanguage): String = when {
        minutes <= 1 -> agoPhrase(1, hour = false, language)
        minutes < 60 -> agoPhrase(minutes, hour = false, language)
        minutes < 120 -> agoPhrase(1, hour = true, language)
        minutes < 180 -> agoPhrase(2, hour = true, language)
        else -> agoPhrase(minutes / 60, hour = true, language)
    }

    private fun agoPhrase(count: Int, hour: Boolean, language: UILanguage): String {
        val category = pluralCategory(count, language)
        val word = when {
            language == UILanguage.Arabic && category == PluralCategory.Two -> if (hour) "ساعتين" else "دقيقتين"
            language == UILanguage.Russian && category == PluralCategory.One && !hour -> "минуту"
            language == UILanguage.Ukrainian && category == PluralCategory.One -> if (hour) "годину" else "хвилину"
            hour -> TimeUnitWord.hour(category, language)
            else -> TimeUnitWord.minute(category, language)
        }
        val counted = countedPhrase(count, word, omitsNumeral(category, language))
        return when (language) {
            UILanguage.Russian -> "$counted назад"
            UILanguage.Ukrainian -> "$counted тому"
            UILanguage.Arabic -> "قبل $counted"
            UILanguage.French -> "il y a $counted"
            UILanguage.Spanish -> "hace $counted"
            UILanguage.German -> "vor $counted"
            UILanguage.Portuguese -> "há $counted"
            UILanguage.ChineseSimplified -> "${counted}前"
            UILanguage.Hindi -> "$counted पहले"
            UILanguage.Amharic -> "ከ$count $word በፊት"
            UILanguage.Hebrew, UILanguage.English -> counted
        }
    }
}
