package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.UILanguage

object QuickPhrases {
    fun shown(stored: List<String>, language: UILanguage): List<String> = AppSettings.displayedQuickPhrases(stored, language)

    fun adding(phrase: String, stored: List<String>, language: UILanguage): List<String> {
        val trimmed = phrase.trim()
        val shown = shown(stored, language)
        if (trimmed.isEmpty() || trimmed in shown) return stored
        return shown + trimmed
    }
}
