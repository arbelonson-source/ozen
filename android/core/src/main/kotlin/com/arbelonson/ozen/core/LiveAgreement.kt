package com.arbelonson.ozen.core

class LiveAgreement {
    private var lastWords: List<String> = emptyList()
    private var held: MutableList<String> = ArrayList()

    fun settle(text: String): String {
        val words = splitWords(text)
        val result = settle(text, words)
        lastWords = words
        return result
    }

    private fun settle(text: String, words: List<String>): String {
        if (words.isEmpty()) return text

        var shown: MutableList<String>? = null
        if (words.size >= held.size) {
            for (index in held.indices) {
                if (words[index] != held[index] && index in lastWords.indices && lastWords[index] == words[index]) {
                    held[index] = words[index]
                }
            }
            val changed = held.indices.filter { words[it] != held[it] }
            if (changed.isNotEmpty() && changed.size <= MAXIMUM_HELD_CHANGES && changed.size * 3 <= held.size) {
                shown = words.toMutableList()
                for (index in changed) shown[index] = held[index]
            } else if (changed.isNotEmpty()) {
                held.subList(changed[0], held.size).clear()
            }
        } else {
            held.subList(agreeing(held, words), held.size).clear()
        }

        val agreed = agreeing(lastWords, words)
        if (agreed > held.size) held.addAll(words.subList(held.size, agreed))
        return shown?.joinToString(" ") ?: text
    }

    fun copy(): LiveAgreement {
        val copy = LiveAgreement()
        copy.lastWords = lastWords
        copy.held = held.toMutableList()
        return copy
    }

    override fun equals(other: Any?): Boolean =
        other is LiveAgreement && lastWords == other.lastWords && held == other.held

    override fun hashCode(): Int = 31 * lastWords.hashCode() + held.hashCode()

    private fun agreeing(first: List<String>, second: List<String>): Int {
        var count = 0
        while (count < first.size && count < second.size && first[count] == second[count]) count++
        return count
    }

    private fun splitWords(text: String): List<String> {
        val words = ArrayList<String>(lastWords.size + 1)
        var start = -1
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (start >= 0) words.add(wordAt(text, start, index, words.size))
                start = -1
            } else if (start < 0) {
                start = index
            }
            index += Character.charCount(codePoint)
        }
        if (start >= 0) words.add(wordAt(text, start, text.length, words.size))
        return words
    }

    private fun wordAt(text: String, start: Int, end: Int, position: Int): String {
        val before = lastWords.getOrNull(position)
        if (before != null && before.length == end - start && text.regionMatches(start, before, 0, end - start)) return before
        return text.substring(start, end)
    }

    companion object {
        const val MAXIMUM_HELD_CHANGES = 2
    }
}
