package com.arbelonson.ozen.core

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.math.ln

/**
 * The numbers the phone's engine works out from a pass's tokens. WhisperKit
 * does the same from its own result; whisper.cpp hands over the tokens and
 * their probabilities, so they are computed here.
 */
internal object WhisperPassScoring {
    private val noSpaceLanguages = setOf("zh", "ja", "th", "lo", "my", "yue")

    private val punctuationTypes = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
    )

    fun logProbability(token: WhisperToken): Float = ln(token.probability.toDouble()).toFloat()

    /**
     * WhisperKit's own definition (`TextUtilities.compressionRatio(of: [Int])`):
     * the word tokens' ids as little-endian 32-bit numbers over their size
     * deflated (Apple's zlib is raw deflate at level 5). It is over the token
     * numbers, not the text, which is why a short repeat scores higher here
     * than on the home computer (see `WhisperSegmentSummary.compressionRatio`).
     */
    fun compressionRatio(segment: WhisperSegment, specialTokenBegin: Int): Float {
        val ids = segment.tokens.filter { it.id < specialTokenBegin }.map { it.id }
        val raw = ByteArray(ids.size * 4)
        for ((index, id) in ids.withIndex()) {
            for (byte in 0 until 4) raw[index * 4 + byte] = (id shr (8 * byte)).toByte()
        }
        val deflater = Deflater(5, true)
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(raw.size + 64)
        var compressed = 0
        while (!deflater.finished()) compressed += deflater.deflate(buffer)
        deflater.end()
        return if (compressed == 0) Float.POSITIVE_INFINITY else raw.size.toFloat() / compressed.toFloat()
    }

    /** The segment's score on the scale its cutoffs were measured on (see `WhisperSegmentSummary.averageLogprob`). */
    fun averageLogprob(segment: WhisperSegment, specialTokenBegin: Int): Float? =
        WhisperSegmentSummary.averageLogprob(
            segment.tokens.filter { it.id < specialTokenBegin }.map { logProbability(it) },
        )

    /**
     * The words of [segment] the model was least sure of. Special tokens
     * (timestamps, the language tag) aren't words and are left out, with
     * their scores, before the pieces are put back into words.
     */
    fun uncertainWords(segment: WhisperSegment, specialTokenBegin: Int, languageCode: String): List<String> {
        val pieces = segment.tokens.filter { it.id < specialTokenBegin }
        if (pieces.isEmpty()) return emptyList()
        val logprobs = pieces.map { logProbability(it) }
        val noSpaces = languageCode.substringBefore('-').lowercase() in noSpaceLanguages
        val split = if (noSpaces) splitOnUnicode(pieces) else splitOnSpaces(pieces)
        var next = 0
        val perWord = ArrayList<List<Float>>()
        for (group in split) {
            val end = minOf(next + group.size, logprobs.size)
            perWord.add(if (next < end) logprobs.subList(next, end).toList() else emptyList())
            next = end
        }
        return UncertainWords.pick(split.map { decode(it) }, perWord)
    }

    private fun decode(tokens: List<WhisperToken>): String {
        val bytes = ByteArrayOutputStream()
        for (token in tokens) bytes.write(token.piece)
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun splitOnUnicode(tokens: List<WhisperToken>): List<List<WhisperToken>> {
        val full = decode(tokens)
        val groups = ArrayList<List<WhisperToken>>()
        var current = ArrayList<WhisperToken>()
        for (token in tokens) {
            current.add(token)
            val at = decode(current).indexOf('�')
            if (at < 0 || full.startsWith("�", at)) {
                groups.add(current)
                current = ArrayList()
            }
        }
        return groups
    }

    private fun isSpace(char: Char): Boolean = char == '\t' || Character.getType(char).toByte() == Character.SPACE_SEPARATOR

    private fun splitOnSpaces(tokens: List<WhisperToken>): List<List<WhisperToken>> {
        val words = ArrayList<MutableList<WhisperToken>>()
        for (group in splitOnUnicode(tokens)) {
            val subword = decode(group)
            val stripped = subword.trim { isSpace(it) }
            val punctuation = stripped.isNotEmpty() && stripped.codePointCount(0, stripped.length) == 1 &&
                Character.getType(stripped.codePointAt(0)).toByte() in punctuationTypes
            if (group.first().isSpecial || subword.startsWith(" ") || punctuation || words.isEmpty()) {
                words.add(group.toMutableList())
            } else {
                words.last().addAll(group)
            }
        }
        return words
    }
}
