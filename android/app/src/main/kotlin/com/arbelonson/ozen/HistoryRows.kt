package com.arbelonson.ozen

import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SavedLineSpeech
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

data class ConversationRow(
    val id: UUID,
    val title: String?,
    val time: String,
    val duration: String?,
    val speakers: String?,
    val preview: String,
    val lines: String,
) {
    companion object {
        fun of(summary: TranscriptSessionSummary, zone: ZoneId = ZoneId.systemDefault()) = ConversationRow(
            id = summary.id,
            title = summary.title,
            time = clockTime(summary.startedAt, zone),
            duration = summary.durationSeconds?.let { ConversationStats.minutesText(it) },
            speakers = summary.speakerNames.takeIf { it.isNotEmpty() }?.let { names ->
                names.take(3).joinToString(", ") + if (names.size > 3) tr(" ועוד", " and more") else ""
            },
            preview = CaptionLayout.directed(summary.preview),
            lines = ConversationStats.linesText(summary.segmentCount),
        )
    }
}

data class SavedLine(val id: UUID, val time: String?, val speaker: String?, val text: String, val spoken: String) {
    companion object {
        fun of(record: TranscriptSessionRecord, zone: ZoneId = ZoneId.systemDefault()): List<SavedLine> {
            val timed = CaptionLayout.timeMarkedLineIDs(record.segments)
            return record.segments.mapIndexed { index, segment ->
                val previous = record.segments.getOrNull(index - 1)
                val time = if (segment.id in timed) clockTime(segment.startTimestamp, zone) else null
                SavedLine(
                    id = segment.id,
                    time = time,
                    speaker = segment.speakerName.takeIf { CaptionLayout.showsSpeakerLabel(segment, previous) },
                    text = CaptionLayout.displayText(segment.text),
                    spoken = SavedLineSpeech.label(segment, time, uncertain = false),
                )
            }
        }
    }
}

fun clockTime(at: Double, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Localization.locale)
        .format(Instant.ofEpochMilli((at * 1_000).toLong()).atZone(zone))
