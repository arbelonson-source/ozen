package com.arbelonson.ozen

internal object SpeechChunks {
    fun of(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val pieces = mutableListOf<String>()
        var rest = text
        while (rest.length > limit) {
            var cut = sentenceEnd(rest, limit) ?: rest.lastIndexOf(' ', limit).takeIf { it > 0 } ?: limit
            if (cut > 1 && Character.isHighSurrogate(rest[cut - 1])) cut -= 1
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trimStart()
        }
        pieces += rest.trim()
        return pieces.filter { it.isNotEmpty() }
    }

    private fun sentenceEnd(text: String, limit: Int): Int? =
        (limit downTo 1).firstOrNull { (text[it - 1] in ENDS && text[it].isWhitespace()) || text[it] == '\n' }

    private val ENDS = setOf('.', '?', '!', Char(0x061F), Char(0x0964), Char(0x1362))
}
