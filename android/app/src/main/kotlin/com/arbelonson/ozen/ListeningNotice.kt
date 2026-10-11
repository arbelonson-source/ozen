package com.arbelonson.ozen

import com.arbelonson.ozen.core.LockScreenCaptionContent
import com.arbelonson.ozen.core.tr

object ListeningNotice {
    fun glance(content: LockScreenCaptionContent): String {
        val newest = content.lines.lastOrNull() ?: return tr("הכתוביות יופיעו כאן.", "Captions will appear here.")
        val above = content.lines.getOrNull(content.lines.lastIndex - 1)
        return said(newest.text, newest.speaker ?: above?.speaker?.takeIf { newest.sameSpeakerAsAbove })
    }

    fun opened(content: LockScreenCaptionContent): String? =
        content.lines.takeIf { it.size > 1 }?.joinToString("\n") { said(it.text, it.speaker) }

    private fun said(text: String, name: String?) = if (name == null) text else "$name: $text"
}
