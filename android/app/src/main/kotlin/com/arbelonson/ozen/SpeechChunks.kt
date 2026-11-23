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

    fun queue(
        pieces: List<String>,
        ids: List<String> = pieces.map { java.util.UUID.randomUUID().toString() },
        offer: (piece: String, id: String, flush: Boolean) -> Boolean,
    ): Queued {
        val taken = mutableListOf<String>()
        for ((at, piece) in pieces.withIndex()) {
            if (!offer(piece, ids[at], at == 0)) return Queued(taken, complete = false)
            taken += ids[at]
        }
        return Queued(taken, complete = true)
    }
}
