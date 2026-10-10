package com.arbelonson.ozen.core

object SavedLineSpeech {
    fun label(segment: SavedSegment, time: String?, uncertain: Boolean): String {
        var line = segment.text
        val name = segment.speakerName
        if (!name.isNullOrEmpty() && !TranscriptSessionSummary.isUnknownSpeakerLabel(name)) {
            line = "$name: $line"
        }
        if (uncertain) line = tr("ייתכן שלא נשמע נכון. ", "May not have been heard correctly. ") + line
        if (segment.isStarred) line = tr("מסומן כחשוב. ", "Marked as important. ") + line
        if (time != null) line = "$time. $line"
        return line
    }
}
