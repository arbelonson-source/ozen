package com.arbelonson.ozen.core

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.exp

/**
 * Which words of a caption line to mark as doubtful.
 *
 * A mark on the whole line says "something here may be wrong" without saying
 * what; someone who can't hear the room has no way to tell whether it was the
 * hour of the appointment or the "thank you" after it. Whisper gives a
 * probability for every piece of a word it writes, so the doubt can be put on
 * the word itself.
 */
object UncertainWords {
    /**
     * A word counts as doubtful when the model's average probability for its
     * pieces is under this. Stricter than the line's 0.8: one word's score
     * swings far more than a line's average, and the first piece of any word
     * is often a toss-up between good candidates.
     */
    const val uncertainBelow: Float = 0.25f

    /** A line where most words are marked says nothing a line mark doesn't. */
    const val maximumShare = 0.5

    /** Stands before a copy the model was sure of (see [pick]). Normalized words never hold it: it is a symbol, and those are stripped. */
    internal const val sureCopy = "~"

    /**
     * The doubtful ones among [words], given the log-probabilities of each
     * word's pieces, as the normalized words [ranges] looks for.
     *
     * Short words come back within a line ("I", "no"), and the model can guess
     * at one copy and know the next. So every copy of a doubtful word is listed
     * in order, a sure one behind [sureCopy], and only the copy it guessed at
     * is marked. A line built from several passes joins their lists in the same
     * order its text joins.
     */
    fun pick(words: List<String>, logprobs: List<List<Float>>): List<String> {
        val copies = mutableListOf<Pair<String, Boolean>>()
        for ((word, pieces) in words.zip(logprobs)) {
            val key = normalize(word)
            if (key.isEmpty()) continue
            val finite = pieces.filter { it.isFinite() }
            val doubtful = finite.isNotEmpty() &&
                exp(finite.fold(0f) { sum, value -> sum + value } / finite.size.toFloat()) < uncertainBelow
            copies.add(key to doubtful)
        }
        val picked = copies.filter { it.second }.map { it.first }.toSet()
        if (copies.isEmpty() || picked.size.toDouble() / copies.size.toDouble() > maximumShare) return emptyList()
        return copies.filter { it.first in picked }.map { if (it.second) it.first else sureCopy + it.first }
    }

    /**
     * Where the doubtful words sit in [text], whole words only, as ranges of
     * UTF-16 offsets. A word listed with a sure copy is marked copy by copy, in
     * order; one never listed as sure is marked wherever it appears.
     */
    fun ranges(text: String, words: List<String>): List<OpenEndRange<Int>> {
        if (words.isEmpty()) return emptyList()
        val copies = HashMap<String, MutableList<Boolean>>()
        for (entry in words) {
            val isSure = entry.startsWith(sureCopy)
            copies.getOrPut(if (isSure) entry.substring(sureCopy.length) else entry) { mutableListOf() }.add(!isSure)
        }
        val byCopy = copies.filter { it.value.contains(false) }.keys
        val offsets = characterOffsets(text)
        val count = offsets.size - 1
        fun isSpace(character: Int) = text.codePointAt(offsets[character]).let(::isUncertainWhitespace)
        val ranges = mutableListOf<OpenEndRange<Int>>()
        var index = 0
        while (index < count) {
            if (isSpace(index)) {
                index += 1
                continue
            }
            var end = index
            while (end < count && !isSpace(end)) end++
            val start = offsets[index]
            val stop = offsets[end]
            val key = normalize(text.substring(start, stop))
            if (key in byCopy) {
                val remaining = copies[key]
                if (remaining != null && remaining.isNotEmpty() && remaining.removeAt(0)) ranges.add(IntRange(start, stop - 1))
            } else if (copies[key] != null) {
                ranges.add(IntRange(start, stop - 1))
            }
            index = end
        }
        return ranges
    }

    /**
     * The doubtful words as they are written in [text], in order, for a screen
     * reader, which can't see the dotted underline.
     */
    fun spoken(text: String, words: List<String>): List<String> = ranges(text, words).map {
        val scalars = text.substring(it.start, it.endExclusive).codePoints().toArray()
        var from = 0
        var to = scalars.size
        while (from < to && isUncertainPunctuation(scalars[from])) from++
        while (to > from && isUncertainPunctuation(scalars[to - 1])) to--
        String(scalars, from, to - from)
    }

    internal fun normalize(word: String): String {
        val scalars = WhisperResultFilter.normalize(word).codePoints().toArray()
        var from = 0
        var to = scalars.size
        while (from < to && isUncertainSpace(scalars[from])) from++
        while (to > from && isUncertainSpace(scalars[to - 1])) to--
        return String(scalars, from, to - from).lowercase(Locale.ROOT)
    }

    private fun characterOffsets(text: String): List<Int> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val result = mutableListOf(iterator.first())
        var next = iterator.next()
        while (next != BreakIterator.DONE) {
            result.add(next)
            next = iterator.next()
        }
        return result
    }

    private fun isUncertainWhitespace(codePoint: Int): Boolean =
        codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
            codePoint in 0x2000..0x200A || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
            codePoint == 0x205F || codePoint == 0x3000

    private fun isUncertainSpace(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> codePoint in 0x09..0x0D || codePoint == 0x85
    }

    private fun isUncertainPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        -> true
        else -> false
    }
}
