package com.arbelonson.ozen

import com.arbelonson.ozen.core.CaptionConfidence
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.NumberEmphasis
import com.arbelonson.ozen.core.SavedLineSpeech
import com.arbelonson.ozen.core.SavedSegment
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

data class SavedLine(
    val id: UUID,
    val time: String?,
    val speaker: String?,
    val text: String,
    val spoken: String,
    val starred: Boolean = false,
    val unsure: Boolean = false,
    val copied: String = text,
) {
    companion object {
        /** [markUncertain]: the caption screen's question mark, kept on saved lines as on the iPhone. */
        fun of(record: TranscriptSessionRecord, zone: ZoneId = ZoneId.systemDefault(), markUncertain: Boolean = false): List<SavedLine> {
            val timed = CaptionLayout.timeMarkedLineIDs(record.segments)
            return record.segments.mapIndexed { index, segment ->
                val previous = record.segments.getOrNull(index - 1)
                val time = if (segment.id in timed) clockTime(segment.startTimestamp, zone) else null
                val unsure = markUncertain && CaptionConfidence.isUncertain(segment, record.engine, record.modelVariant)
                SavedLine(
                    id = segment.id,
                    time = time,
                    speaker = segment.speakerName.takeIf { CaptionLayout.showsSpeakerLabel(segment, previous) },
                    text = CaptionLayout.displayText(segment.text),
                    spoken = SavedLineSpeech.label(segment, time, uncertain = unsure),
                    starred = segment.isStarred,
                    unsure = unsure,
                    copied = CaptionLayout.copiedText(segment.text),
                )
            }
        }
    }
}

fun clockTime(at: Double, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Localization.locale)
        .format(Instant.ofEpochMilli((at * 1_000).toLong()).atZone(zone))

object HistoryTexts {
    fun savingFooter(saving: Boolean, size: String, retention: HistoryRetention): String {
        val base = if (saving) {
            tr("השיחות נשמרות בטלפון הזה (%1). אפשר למחוק אותן בכל רגע.", "Conversations are saved on this phone (%1). They can be deleted anytime.", listOf(size))
        } else {
            tr(
                "שיחות חדשות לא נשמרות. השיחות שכבר נשמרו נשארות בטלפון הזה (%1). אפשר למחוק אותן בכל רגע.",
                "New conversations are not being saved. Those saved before stay on this phone (%1). They can be deleted anytime.",
                listOf(size),
            )
        }
        if (retention == HistoryRetention.Forever) return base
        return base + tr(" שיחות עם שורה מסומנת או עם שם נשמרות תמיד.", " Conversations with a starred line or a name are always kept.")
    }

    fun expiryWarning(count: Int): String = when (count) {
        1 -> tr("שיחה ישנה אחת תימחק עכשיו", "1 old conversation will be deleted now")
        2 -> tr("שתי שיחות ישנות יימחקו עכשיו", "2 old conversations will be deleted now")
        else -> ConversationStats.oldConversationsDeletedText(count)
    }

    fun emptyMessage(query: String, saving: Boolean): String = when {
        query.isNotBlank() -> tr("לא נמצא כלום עבור \"%1\".", "Nothing found for “%1”.", listOf(query.trim()))
        saving -> tr("עדיין אין שיחות שמורות.", "No saved conversations yet.")
        else -> tr("שמירת שיחות כבויה.", "Saving conversations is off.")
    }

    fun name(retention: HistoryRetention): String = when (retention) {
        HistoryRetention.Forever -> tr("אף פעם", "Never")
        HistoryRetention.Year -> tr("אחרי שנה", "After a year")
        HistoryRetention.ThreeMonths -> tr("אחרי 3 חודשים", "After 3 months")
        HistoryRetention.Month -> tr("אחרי חודש", "After a month")
        HistoryRetention.Week -> tr("אחרי שבוע", "After a week")
    }
}

object SearchMatches {
    fun nextTitle(current: Int, count: Int): String =
        tr("המקום הבא (%1 מתוך %2)", "Next match (%1 of %2)", listOf("${current + 1}", "$count"))

    fun position(index: Int, old: List<UUID>, new: List<UUID>): Int? =
        old.getOrNull(index)?.let { new.indexOf(it) }?.takeIf { it >= 0 }

    /** With no search the same button steps through the starred lines, from the first. */
    fun starredTitle(current: Int, count: Int, jumped: Boolean): String = if (jumped) {
        tr("הסימון הבא (%1 מתוך %2)", "Next starred (%1 of %2)", listOf("${current + 1}", "$count"))
    } else {
        tr("לשורות המסומנות (%1)", "To starred lines (%1)", listOf("$count"))
    }
}

/**
 * "What time was the appointment again?": the lines of a saved conversation
 * with a time, an amount or a phone number in them, gathered at its top as
 * on the iPhone, the first twelve listed and the rest counted.
 */
class NumbersMentioned(val listed: List<SavedSegment>, val moreText: String?) {
    companion object {
        const val LISTED = 12

        fun of(record: TranscriptSessionRecord): NumbersMentioned {
            val numbered = record.segments.filter { NumberEmphasis.hasListableNumber(it.text) }
            val more = numbered.size - LISTED
            return NumbersMentioned(
                listed = numbered.take(LISTED),
                moreText = if (more > 0) {
                    tr("ועוד %1 עם מספרים בהמשך השיחה", "Plus %1 with numbers later in the conversation", listOf(ConversationStats.linesText(more)))
                } else {
                    null
                },
            )
        }
    }
}
