package com.arbelonson.ozen

import com.arbelonson.ozen.core.LockScreenCaptionContent
import com.arbelonson.ozen.core.LockScreenCaptions
import com.arbelonson.ozen.core.tr

object ListeningNotice {
    private const val FEWEST_LETTERS = 12

    fun glance(content: LockScreenCaptionContent, rowCharacters: Int = Int.MAX_VALUE): String {
        val newest = content.lines.lastOrNull() ?: return tr("הכתוביות יופיעו כאן.", "Captions will appear here.")
        val above = content.lines.getOrNull(content.lines.lastIndex - 1)
        val name = newest.speaker ?: above?.speaker?.takeIf { newest.sameSpeakerAsAbove }
        val room = name?.let { rowCharacters - it.codePointCount(0, it.length) - 2 }
        if (name != null && room != null && room >= FEWEST_LETTERS) return said(LockScreenCaptions.tail(newest.text, room), name)
        return LockScreenCaptions.tail(newest.text, maxOf(rowCharacters, FEWEST_LETTERS))
    }

    fun rowCharacters(shortSideDp: Int, fontScale: Float): Int =
        maxOf(FEWEST_LETTERS, ((shortSideDp - 112) / (8f * fontScale)).toInt())

    fun opened(content: LockScreenCaptionContent): String? =
        content.lines.takeIf { it.isNotEmpty() }?.joinToString("\n") { said(it.text, it.speaker) }

    private fun said(text: String, name: String?) = if (name == null) text else "$name: $text"
}
