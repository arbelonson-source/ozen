package com.arbelonson.ozen

import com.arbelonson.ozen.core.AlertSuggestions
import com.arbelonson.ozen.core.HebrewText
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.UILanguage
import java.util.UUID

object ImportantWords {
    fun listed(phrase: String, alerts: List<KeywordAlert>): KeywordAlert? {
        val typed = HebrewText.normalize(phrase)
        if (typed.isEmpty()) return null
        return alerts.firstOrNull { HebrewText.normalize(it.phrase) == typed }
    }

    fun adding(phrase: String, alerts: List<KeywordAlert>): List<KeywordAlert> {
        val trimmed = phrase.trim()
        if (trimmed.isEmpty()) return alerts
        val listed = listed(trimmed, alerts) ?: return alerts + KeywordAlert(phrase = trimmed)
        return if (listed.isEnabled) alerts else switched(listed.id, true, alerts)
    }

    fun switched(id: UUID, on: Boolean, alerts: List<KeywordAlert>): List<KeywordAlert> =
        alerts.map { if (it.id == id) it.copy(isEnabled = on) else it }

    fun removing(id: UUID, alerts: List<KeywordAlert>): List<KeywordAlert> = alerts.filter { it.id != id }

    fun suggestions(vocabulary: List<String>, alerts: List<KeywordAlert>, spoken: UILanguage): List<String> {
        val seen = mutableSetOf<String>()
        return (vocabulary + AlertSuggestions.offeredWords(spoken)).filter { word ->
            val normalized = HebrewText.normalize(word)
            seen.add(normalized) && alerts.none { HebrewText.normalize(it.phrase) == normalized }
        }.take(8)
    }
}
