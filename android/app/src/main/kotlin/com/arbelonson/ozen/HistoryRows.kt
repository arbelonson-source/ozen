package com.arbelonson.ozen

import com.arbelonson.ozen.core.CaptionConfidence
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.ConversationStats
import com.arbelonson.ozen.core.HistoryDays
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.NumberEmphasis
import com.arbelonson.ozen.core.PhoneNumbers
import com.arbelonson.ozen.core.SavedLineSpeech
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.StarredLine
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
    val starred: Int = 0,
    val starredSpoken: String? = null,
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
            starred = summary.starredCount,
            starredSpoken = summary.starredCount.takeIf { it > 0 }?.let {
                ConversationStats.linesText(it, adjective = "מסומנת" to "מסומנות", englishAdjective = "starred")
            },
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
    val phones: List<PhoneNumbers.Match> = emptyList(),
    /** Laid out from the right, as `CaptionLayout.readsRightToLeft` says for its words. */
    val rightToLeft: Boolean = true,
    /** The voice it was heard in, which with the name picks the speaker's colour. */
    val cluster: Int? = null,
) {
    companion object {
        /** [markUncertain]: the caption screen's question mark, kept on saved lines as on the iPhone. */
        fun of(record: TranscriptSessionRecord, zone: ZoneId = ZoneId.systemDefault(), markUncertain: Boolean = false, languageCode: String = "he"): List<SavedLine> {
            val timed = CaptionLayout.timeMarkedLineIDs(record.segments)
            return record.segments.mapIndexed { index, segment ->
                val previous = record.segments.getOrNull(index - 1)
                val time = if (segment.id in timed) clockTime(segment.startTimestamp, zone) else null
                val unsure = markUncertain && CaptionConfidence.isUncertain(segment, record.engine, record.modelVariant)
                SavedLine(
                    id = segment.id,
                    time = time,
                    speaker = segment.speakerName.takeIf { CaptionLayout.showsSpeakerLabel(segment, previous) },
                    text = CaptionLayout.displayText(segment.text, languageCode),
                    spoken = SavedLineSpeech.label(segment, time, uncertain = unsure),
                    starred = segment.isStarred,
                    unsure = unsure,
                    copied = CaptionLayout.copiedText(segment.text),
                    phones = PhoneNumbers.matches(segment.text),
                    rightToLeft = CaptionLayout.readsRightToLeft(segment.text, languageCode),
                    cluster = segment.speakerClusterID,
                )
            }
        }

        /**
         * A line on the starred list, as on the iPhone: always with its time,
         * a "Speaker 2" left out, and not read as starred, which the list
         * already says.
         */
        fun of(line: StarredLine, zone: ZoneId = ZoneId.systemDefault(), markUncertain: Boolean = false, languageCode: String = "he"): SavedLine {
            val segment = line.segment
            val time = clockTime(segment.startTimestamp, zone)
            val speaker = segment.speakerName?.takeUnless { it.isBlank() || TranscriptSessionSummary.isGenericLabel(it) }
            val unsure = markUncertain && line.isUncertain
            return SavedLine(
                id = segment.id,
                time = time,
                speaker = speaker,
                text = CaptionLayout.displayText(segment.text, languageCode),
                spoken = SavedLineSpeech.label(segment.copy(speakerName = speaker, isStarred = false), time, uncertain = unsure),
                starred = true,
                unsure = unsure,
                copied = CaptionLayout.copiedText(segment.text),
                rightToLeft = CaptionLayout.readsRightToLeft(segment.text, languageCode),
                cluster = segment.speakerClusterID,
            )
        }
    }
}

fun clockTime(at: Double, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Localization.locale)
        .format(Instant.ofEpochMilli((at * 1_000).toLong()).atZone(zone))

/** A conversation's day and the time it started, over its lines and over each group of starred lines, as on the iPhone. */
fun conversationHeading(startedAt: Double, now: Double, zone: ZoneId = ZoneId.systemDefault()): String {
    val offset = { at: Double -> zone.rules.getOffset(Instant.ofEpochMilli((at * 1_000).toLong())).totalSeconds }
    return tr("%1 בשעה %2", "%1 at %2", listOf(HistoryDays.title(startedAt, now, offset), clockTime(startedAt, zone)))
}

/** The time above talk that picked up after a break, as on the iPhone: with the day when the words before it were on another day. */
fun resumedTime(gap: QuietGap, zone: ZoneId = ZoneId.systemDefault()): String {
    val resumed = Instant.ofEpochMilli((gap.resumedAt * 1_000).toLong()).atZone(zone)
    val before = Instant.ofEpochMilli((gap.lastWordsAt * 1_000).toLong()).atZone(zone)
    if (resumed.toLocalDate() == before.toLocalDate()) return clockTime(gap.resumedAt, zone)
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Localization.locale).format(resumed)
}

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

    /** Said before clearing the captions when the lines would be lost: with History off, or saving to it failing. */
    fun clearWarning(saving: Boolean, failing: Boolean): String? = when {
        !saving -> tr("היסטוריית השיחות כבויה, ולכן הן לא יישמרו.", "History is off, so they won't be saved.")
        failing -> tr("השמירה בהיסטוריה לא עובדת כרגע, ולכן הן לא יישמרו.", "Saving to History isn't working right now, so they won't be saved.")
        else -> null
    }

    fun emptyMessage(query: String, saving: Boolean, speakerFilter: String? = null): String = when {
        query.isNotBlank() -> tr("לא נמצא כלום עבור \"%1\".", "Nothing found for “%1”.", listOf(query.trim()))
        speakerFilter != null -> tr("אין שיחות עם %1.", "No conversations with %1.", listOf(speakerFilter))
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

        fun of(record: TranscriptSessionRecord, decimalComma: Boolean = false): NumbersMentioned {
            val numbered = record.segments.filter { NumberEmphasis.hasListableNumber(it.text, decimalComma) }
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
