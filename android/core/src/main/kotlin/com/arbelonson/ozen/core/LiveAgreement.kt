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

        val shown = words.toMutableList()
        var substituted = false
        if (words.size >= held.size) {
            for (index in held.indices) {
                if (words[index] != held[index] && index in lastWords.indices && lastWords[index] == words[index]) {
                    held[index] = words[index]
                }
            }
            val changed = held.indices.filter { words[it] != held[it] }
            if (changed.isNotEmpty() && changed.size <= MAXIMUM_HELD_CHANGES && changed.size * 3 <= held.size) {
                for (index in changed) shown[index] = held[index]
                substituted = true
            } else if (changed.isNotEmpty()) {
                held = held.subList(0, changed[0]).toMutableList()
            }
        } else {
            held = held.zip(words).takeWhile { (a, b) -> a == b }.map { it.first }.toMutableList()
        }

        val agreed = lastWords.zip(words).takeWhile { (a, b) -> a == b }.size
        if (agreed > held.size) {
            held = (shown.subList(0, held.size) + words.subList(held.size, agreed)).toMutableList()
        }
        return if (substituted) shown.joinToString(" ") else text
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

    private fun splitWords(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (current.isNotEmpty()) {
                    words.add(current.toString())
                    current.setLength(0)
                }
            } else {
                current.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words
    }

    companion object {
        const val MAXIMUM_HELD_CHANGES = 2
    }
}
