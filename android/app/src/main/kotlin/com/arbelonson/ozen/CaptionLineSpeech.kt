package com.arbelonson.ozen

import com.arbelonson.ozen.core.tr

object CaptionLineSpeech {
    fun label(text: String, speaker: String?, starred: Boolean, unsure: Boolean): String {
        var line = if (speaker != null) "$speaker: $text" else text
        if (unsure) line = tr("ייתכן שלא נשמע נכון. ", "May not have been heard correctly. ") + line
        return if (starred) tr("מסומן כחשוב. %1", "Marked as important. %1", listOf(line)) else line
    }

    fun state(important: Boolean, doubtful: List<String>): String? = listOfNotNull(
        tr("מכילה מילה חשובה", "Contains an important word").takeIf { important },
        doubtful.takeIf { it.isNotEmpty() }?.let { tr("מילים שאולי לא נשמעו נכון: %1", "Words that may be wrong: %1", listOf(it.joinToString(", "))) },
    ).joinToString(". ").ifEmpty { null }
}
