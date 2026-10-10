package com.arbelonson.ozen.core

import java.text.BreakIterator
import java.util.Locale

private fun vocabularyGraphemes(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return result
}

private fun isVocabularyTrimmed(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> codePoint in 0x09..0x0D || codePoint == 0x85 || codePoint == ','.code || codePoint == ';'.code ||
            codePoint == 0x060C
    }

/**
 * Names and words the recognizers should expect. Family members' names are
 * the single biggest source of wrong captions in a home - "Avi" becomes
 * "aval" ("but"), "Ruti" becomes "Rotem" - and every engine accepts hints:
 * Apple's recognizer via `contextualStrings`, Whisper via a text prompt the
 * decoder is conditioned on before it hears any audio, the home computer and
 * each cloud service in its own way.
 */
object VocabularyHints {
    const val maximumTerms = 200
    const val maximumTermLength = 40

    /**
     * Trims, drops empties and duplicates (ignoring case and niqqud), clips
     * over-long entries and caps the list, keeping first-seen order so the
     * user's most important names stay at the front - the Whisper prompt has a
     * token budget and is cut from the end.
     */
    fun normalized(terms: List<String>): List<String> {
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (raw in terms) {
            val trimmed = cleaned(raw)
            if (trimmed.isEmpty()) continue
            val clipped = vocabularyGraphemes(trimmed).take(maximumTermLength).joinToString("")
            val key = dedupKey(clipped)
            if (key.isEmpty() || key in seen) continue
            seen.add(key)
            result.add(clipped)
            if (result.size == maximumTerms) break
        }
        return result
    }

    /**
     * The entry already on the list that [term] would duplicate, compared the
     * way [normalized] compares, so the screen can say so instead of clearing
     * the field as if the word had been added.
     */
    fun listedEntry(term: String, terms: List<String>): String? {
        val key = dedupKey(vocabularyGraphemes(cleaned(term)).take(maximumTermLength).joinToString(""))
        if (key.isEmpty()) return null
        return terms.firstOrNull { dedupKey(it) == key }
    }

    /**
     * A comma or semicolon typed after a name, as in a written list, is the
     * list's and not the name's: kept, the prompt read "Avi,, Ruti.". Whisper's
     * control text ("<|endoftext|>") pasted into an entry would reach the home
     * computer's prompt as a real control token.
     */
    internal fun cleaned(term: String): String {
        val scalars = WhisperResultFilter.stripSpecialTokens(term).codePoints().toArray()
        var from = 0
        var to = scalars.size
        while (from < to && isVocabularyTrimmed(scalars[from])) from++
        while (to > from && isVocabularyTrimmed(scalars[to - 1])) to--
        return String(scalars, from, to - from)
    }

    /**
     * Case and niqqud don't make a different word, but a geresh does: "tzips"
     * written with a geresh isn't "tsips" without one, and stripping it as
     * punctuation made the second impossible to add once the first was
     * listed. A typed apostrophe or quote counts as the same mark, curled by
     * Smart Punctuation or not.
     */
    internal fun dedupKey(term: String): String {
        val marked = term
            .replace("׳", "ʹ")
            .replace("'", "ʹ")
            .replace("‘", "ʹ")
            .replace("’", "ʹ")
            .replace("״", "ʺ")
            .replace("\"", "ʺ")
            .replace("“", "ʺ")
            .replace("”", "ʺ")
        return HebrewText.normalize(marked).lowercase(Locale.ROOT)
    }

    /**
     * The terms to actually prime a recognizer with: the person's own
     * vocabulary list, plus every currently enabled keyword alert's phrase that
     * isn't on it already. A word saved only as an alert ("Grandma",
     * "Ambulance") is at least as important to get right as one saved to the
     * plain vocabulary list - missing it there is exactly the caption the alert
     * exists to catch - so it should not need to be typed twice to help the
     * recognizer spell it right. Alerts are appended after the vocabulary so a
     * name the reader chose to list on purpose keeps priority if the combined
     * list is over [maximumTerms].
     */
    fun combining(vocabulary: List<String>, keywordAlerts: List<KeywordAlert>): List<String> {
        val alertPhrases = keywordAlerts.filter { it.isEnabled }.map { it.phrase }
        return normalized(vocabulary + alertPhrases)
    }

    /**
     * The text Whisper is primed with. A plain comma-separated list is what the
     * model was trained to treat as "previous context": it biases spelling
     * towards these forms without the model trying to transcribe the prompt
     * itself.
     */
    fun whisperPrompt(terms: List<String>): String {
        val cleaned = normalized(terms)
        if (cleaned.isEmpty()) return ""
        return cleaned.joinToString(", ") + "."
    }

    /**
     * [whisperPrompt] with only the names from the top of the list whose
     * prompt, as encoded with its leading space, fits [budget] tokens. Cut by
     * tokens instead, a long list ended in half a name, in front of every
     * line: with 21 Hebrew names, the first three letters of "Savta". The home
     * computer cuts the same way (`front_terms`).
     */
    fun whisperPrompt(terms: List<String>, budget: Int, tokens: (String) -> Int): String {
        val kept = mutableListOf<String>()
        for (term in normalized(terms)) {
            if (tokens(" " + (kept + term).joinToString(", ") + ".") > budget) break
            kept.add(term)
        }
        return if (kept.isEmpty()) "" else kept.joinToString(", ") + "."
    }
}

/**
 * Whisper conditioned on a prompt sometimes "hears" the prompt itself in a
 * quiet window: the names list comes back as a caption ("Avi, Ruti, Dani.").
 * Real speech almost never lists several of those names in exactly the order
 * they were typed in, so a caption made only of a run of consecutive list
 * entries is treated as an echo.
 *
 * One name on its own is never an echo. Someone calling "Avi!" across the room
 * is exactly the caption the list exists to get right.
 */
class PromptEchoDetector(terms: List<String>) {
    /** Each term as normalized words, in list order. */
    private val terms: List<List<String>> = VocabularyHints.normalized(terms)
        .map { HebrewText.words(it) }
        .filter { it.isNotEmpty() }
    private val vocabularyWords: Set<String> = this.terms.flatten().toSet()

    val isEmpty: Boolean get() = terms.isEmpty()

    /** How many consecutive list entries a caption must consist of to count as an echo: three, or the whole list when it's shorter than that. */
    internal val minimumRun: Int get() = minOf(3, terms.size)

    fun isEcho(text: String): Boolean {
        if (terms.size < 2) return false
        val words = HebrewText.words(text)
        if (words.isEmpty() || !words.all { it in vocabularyWords }) return false

        for (start in terms.indices) {
            var position = 0
            var index = start
            // Two entries sharing a word (a name, and the same name with a
            // title) is exactly what naming or disambiguating two people sounds
            // like, not a list read back - a word already claimed by an earlier
            // entry in this run can't count toward a later one.
            val claimedWords = HashSet<String>()
            while (index < terms.size && position < words.size) {
                val term = terms[index]
                if (position + term.size > words.size ||
                    words.subList(position, position + term.size) != term ||
                    !term.all { it !in claimedWords }
                ) {
                    break
                }
                claimedWords.addAll(term)
                position += term.size
                index += 1
            }
            val run = index - start
            if (position == words.size && run >= minimumRun) return true
        }
        return false
    }

    override fun equals(other: Any?): Boolean = other is PromptEchoDetector && terms == other.terms

    override fun hashCode(): Int = terms.hashCode()
}
