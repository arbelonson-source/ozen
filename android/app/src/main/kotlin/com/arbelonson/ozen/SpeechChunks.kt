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
        (limit downTo 1).firstOrNull { text[it - 1] in UNSPACED_ENDS || (text[it - 1] in ENDS && text[it].isWhitespace()) || text[it] == '\n' }

    private val UNSPACED_ENDS = setOf(Char(0x3002), Char(0xFF01), Char(0xFF1F), Char(0xFF61))

    private val ENDS = setOf('.', '?', '!', Char(0x061F), Char(0x0964), Char(0x1362))

    class Queued(val ids: List<String>, val complete: Boolean)

    fun queue(pieces: List<String>, offer: (piece: String, id: String, flush: Boolean) -> Boolean): Queued {
        val ids = mutableListOf<String>()
        for ((at, piece) in pieces.withIndex()) {
            val id = java.util.UUID.randomUUID().toString()
            if (!offer(piece, id, at == 0)) return Queued(ids, complete = false)
            ids += id
        }
        return Queued(ids, complete = true)
    }
}
