package com.arbelonson.ozen

import com.arbelonson.ozen.core.LockScreenCaptionContent
import com.arbelonson.ozen.core.LockScreenCaptions
import com.arbelonson.ozen.core.tr
import java.text.BreakIterator
import java.util.Locale

object ListeningNotice {
    private const val FEWEST_LETTERS = 12

    fun glance(content: LockScreenCaptionContent, rowCharacters: Int = Int.MAX_VALUE): String {
        val newest = content.lines.lastOrNull() ?: return tr("הכתוביות יופיעו כאן.", "Captions will appear here.")
        val above = content.lines.getOrNull(content.lines.lastIndex - 1)
        val name = newest.speaker ?: above?.speaker?.takeIf { newest.sameSpeakerAsAbove }
        val row = if (rowCharacters >= Int.MAX_VALUE / 4) Int.MAX_VALUE / 2 else rowCharacters * 2
        val room = name?.let { row - halves("$it: ") }
        if (name != null && room != null && room >= FEWEST_LETTERS * 2) return said(fitted(newest.text, room), name)
        return fitted(newest.text, maxOf(row, FEWEST_LETTERS * 2))
    }

    fun rowCharacters(shortSideDp: Int, fontScale: Float): Int =
        maxOf(FEWEST_LETTERS, ((shortSideDp - 112) / (8f * fontScale)).toInt())

    fun opened(content: LockScreenCaptionContent): String? =
        content.lines.takeIf { it.isNotEmpty() }?.joinToString("\n") { said(it.text, it.speaker) }

    private fun said(text: String, name: String?) = if (name == null) text else "$name: $text"

    private fun fitted(text: String, roomHalves: Int): String {
        var letters = roomHalves / 2
        while (true) {
            val kept = LockScreenCaptions.tail(text, letters)
            if (halves(kept) <= roomHalves || letters <= 2) return kept
            letters--
        }
    }

    private fun halves(text: String): Int {
        val letters = BreakIterator.getCharacterInstance(Locale.ROOT)
        letters.setText(text)
        var total = 0
        var start = letters.first()
        var end = letters.next()
        while (end != BreakIterator.DONE) {
            total += halvesOf(text.codePointAt(start))
            start = end
            end = letters.next()
        }
        return total
    }

    private fun halvesOf(letter: Int): Int = when (letter) {
        in 0x1100..0x115F, in 0x2E80..0xA4CF, in 0xAC00..0xD7A3, in 0xF900..0xFAFF, in 0xFE30..0xFE4F,
        in 0xFF00..0xFF60, in 0xFFE0..0xFFE6, in 0x20000..0x3FFFD, in 0x1200..0x139F, in 0x2D80..0x2DDF -> 4
        in 0x0900..0x097F -> 3
        else -> 2
    }
}
