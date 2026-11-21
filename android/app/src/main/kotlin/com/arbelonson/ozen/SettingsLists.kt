package com.arbelonson.ozen

import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.VocabularyHints
import com.arbelonson.ozen.core.WordFix
import com.arbelonson.ozen.core.WordFixes

class SettingsLists(private val settings: SettingsHolder) {
    class SavedFix(val words: List<String>?, val fixes: List<WordFix>?)

    fun setWords(words: List<String>) = editWords { words }

    fun addWord(word: String) = editWords { listOf(word) + it }

    fun setWordFixes(fixes: List<WordFix>): List<WordFix> {
        var kept = emptyList<WordFix>()
        settings.change {
            it.wordFixes = WordFixes.normalized(fixes)
            kept = it.wordFixes
        }
        return kept
    }

    fun setAlerts(alerts: List<KeywordAlert>) = editAlerts { alerts }

    fun addAlert(phrase: String) = editAlerts { ImportantWords.adding(phrase, it) }

    fun saveWordFix(fix: WordFix, everyTime: Boolean): SavedFix {
        val word = fix.written.trim()
        var words: List<String>? = null
        var fixes: List<WordFix>? = null
        settings.change { current ->
            if (word.isNotEmpty() && current.vocabulary.none { it.equals(word, ignoreCase = true) }) {
                current.vocabulary = VocabularyHints.normalized(listOf(word) + current.vocabulary)
                words = current.vocabulary
            }
            if (everyTime && WordFixes.canAdd(fix, current.wordFixes)) {
                current.wordFixes = WordFixes.normalized(WordFixes.adding(fix, current.wordFixes))
                fixes = current.wordFixes
            }
        }
        return SavedFix(words, fixes)
    }

    private fun editWords(edit: (List<String>) -> List<String>): List<String> {
        var kept = emptyList<String>()
        settings.change {
            it.vocabulary = VocabularyHints.normalized(edit(it.vocabulary))
            kept = it.vocabulary
        }
        return kept
    }

    private fun editAlerts(edit: (List<KeywordAlert>) -> List<KeywordAlert>): List<KeywordAlert> {
        var kept = emptyList<KeywordAlert>()
        settings.change {
            it.keywordAlerts = edit(it.keywordAlerts)
            kept = it.keywordAlerts
        }
        return kept
    }
}
