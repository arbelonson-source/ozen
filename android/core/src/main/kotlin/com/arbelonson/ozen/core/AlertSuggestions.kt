package com.arbelonson.ozen.core

object AlertSuggestions {
    val names = listOf("סבתא", "אמא")
    val words = listOf("סבתא", "אמא", "תרופה", "רופא")

    fun label(word: String, language: UILanguage): String = shown(word, language)

    fun shown(phrase: String, language: UILanguage): String {
        if (language == UILanguage.Hebrew) return phrase
        val word = suggestion(phrase) ?: return phrase
        return meaning(word, language) ?: phrase
    }

    fun vibrationNote(alerts: List<KeywordAlert>, language: UILanguage): String? {
        val phrases = alerts.filter { it.isEnabled }.map { shown(it.phrase, language) }
        if (phrases.isEmpty()) return null
        return tr("הטלפון ירטוט על: ", "The phone will vibrate for: ", language) + phrases.joinToString(", ")
    }

    fun said(match: KeywordMatch, language: UILanguage): String {
        if (language == UILanguage.Hebrew || suggestion(match.phrase) == null) return match.matchedText
        return shown(match.phrase, language)
    }

    internal fun meaning(word: String, language: UILanguage): String? = when (word) {
        "סבתא" -> tr("סבתא", "Grandma", language)
        "אמא" -> tr("אמא", "Mom", language)
        "תרופה" -> tr("תרופה", "Medicine", language)
        "רופא" -> tr("רופא", "Doctor", language)
        else -> null
    }

    private fun suggestion(phrase: String): String? = words.firstOrNull { HebrewText.normalize(it) == HebrewText.normalize(phrase) }
}
