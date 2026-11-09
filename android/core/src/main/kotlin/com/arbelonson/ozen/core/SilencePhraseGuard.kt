package com.arbelonson.ozen.core

import java.util.UUID

class SilencePhraseGuard(phrases: Set<String> = WhisperResultFilter.defaultAmbiguousHallucinations) {
    private val phrases: List<List<String>> = phrases
        .map { wordsOf(it) }
        .filter { it.isNotEmpty() }
        .sortedByDescending { it.size }
    private var lastPhraseAt: Double? = null
    private var shownPhraseLine: UUID? = null
    private var waitingForVoice: UUID? = null

    fun admits(token: TranscriptToken, time: Double): Boolean {
        val words = wordsOf(token.text)
        if (words.isEmpty()) return true
        val matched = phrases(words)
        if (matched == null || matched.map { it in soundTags }.toSet().size != 1) {
            lastPhraseAt = null
            shownPhraseLine = null
            waitingForVoice = null
            return true
        }
        val plausible = matched.size == 1 || isVoicedRepeat(matched, token.voicedChunks)
        if (token.utteranceID == shownPhraseLine && plausible) {
            lastPhraseAt = time
            return true
        }
        val stillThisLine = token.utteranceID == waitingForVoice
        val cameBack = !stillThisLine && (lastPhraseAt?.let { time - it < QUIET_SECONDS } ?: false)
        lastPhraseAt = time
        if (cameBack) {
            waitingForVoice = null
            return false
        }
        if (!plausible) {
            waitingForVoice = token.utteranceID
            return false
        }
        waitingForVoice = null
        shownPhraseLine = token.utteranceID
        return true
    }

    private fun phrases(words: List<String>): List<String>? {
        var index = 0
        val matched = ArrayList<String>()
        while (index < words.size) {
            val phrase = phrases.firstOrNull { startsWith(words, index, it) } ?: return null
            index += phrase.size
            matched.add(phrase.joinToString(" "))
        }
        return matched
    }

    private fun startsWith(words: List<String>, from: Int, phrase: List<String>): Boolean {
        if (from + phrase.size > words.size) return false
        for (offset in phrase.indices) {
            if (words[from + offset] != phrase[offset]) return false
        }
        return true
    }

    companion object {
        const val QUIET_SECONDS: Double = 15.0
        const val MINIMUM_VOICED_CHUNKS_PER_PHRASE: Int = 2

        private val soundTags: Set<String> =
            setOf("מוזיקה", "שירה", "צחוק").map { WhisperResultFilter.normalize(it) }.toSet()

        private fun isVoicedRepeat(matched: List<String>, voicedChunks: Int?): Boolean {
            if (voicedChunks == null || matched.first() in soundTags) return false
            if (matched.map { it.substringBefore(' ') }.toSet().size != 1) return false
            return voicedChunks >= MINIMUM_VOICED_CHUNKS_PER_PHRASE * matched.size
        }

        private fun wordsOf(text: String): List<String> =
            WhisperResultFilter.normalize(text).split(" ").filter { it.isNotEmpty() }
    }
}
