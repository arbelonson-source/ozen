package com.arbelonson.ozen.core

import java.util.UUID

/**
 * What TalkBack reads out, or sends to a braille display, as captions come
 * in; the iPhone's `CaptionAnnouncer`.
 *
 * Someone whose eyesight is failing as well as their hearing can follow a
 * conversation through a screen reader: speech into hearing aids, or a
 * braille display under the fingers. The lines on screen can be read by
 * moving to them, but a live conversation needs new words to arrive by
 * themselves.
 *
 * Only finished lines are announced, since a line that is still changing
 * would be read five times over. Each is announced once, several finished
 * together go out as one announcement, and the speaker's name leads a line
 * when the speaker changes, the way the screen shows it.
 */
class CaptionAnnouncer {
    /** What each line said when it was last announced (or skipped), without a cut-off mark. */
    private val announced = HashMap<UUID, String>()
    private var lastSpeaker: String? = null

    /** The line announced (or skipped) last, for the quiet before the next. */
    private var lastLine: TranscriptSegment? = null

    /**
     * Where the next look starts ([scanStart]): lines before it were
     * finished and handled, and nothing corrects a line that far back.
     */
    private var scannedCount = 0
    private var firstOpenIndex: Int? = null

    /**
     * The text to announce for lines finished since the last call, or null
     * when there is nothing new. [speakerName] returns null for lines whose
     * speaker shouldn't be named; [isUncertain] says which lines carry the
     * screen's question mark.
     */
    fun announcement(
        segments: List<TranscriptSegment>,
        speakerName: (TranscriptSegment) -> String?,
        isUncertain: (TranscriptSegment) -> Boolean = { false },
    ): String? {
        forgetLinesNoLongerShown(segments)
        val parts = mutableListOf<String>()
        val start = scanStart(segments)
        noteScanned(segments, start)
        for (segment in segments.subList(start, segments.size)) {
            if (!segment.isCommitted) continue
            val text = segment.text.trim()
            // A line read out once is read again only if its words changed
            // afterwards (a slow engine correcting one of the last lines),
            // not when a dropped connection only marked it cut off.
            val words = words(text)
            val before = announced[segment.id]
            if (before == words) continue
            announced[segment.id] = words
            if (text.isEmpty()) continue
            val tail = grownTail(before, words)
            if (tail != null) {
                if (tail.isNotEmpty()) parts += tail
                lastSpeaker = speakerName(segment)
                lastLine = segment
                continue
            }
            val name = speakerName(segment)
            // After a quiet stretch the name comes again, as on screen.
            var part = text
            if (name != null && (name != lastSpeaker || CaptionLayout.startsAfterQuiet(segment, lastLine))) part = "$name: $text"
            if (isUncertain(segment)) part = tr("ייתכן שלא נשמע נכון. ", "May not have been heard correctly. ") + part
            parts += part
            lastSpeaker = name
            lastLine = segment
        }
        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }

    /**
     * Treats every line already there as read, so turning the screen
     * reader (or the setting) on mid-conversation doesn't read out the
     * whole backlog.
     */
    fun skipLinesSoFar(segments: List<TranscriptSegment>) {
        forgetLinesNoLongerShown(segments)
        val start = scanStart(segments)
        noteScanned(segments, start)
        for (segment in segments.subList(start, segments.size)) {
            if (segment.isCommitted) announced[segment.id] = words(segment.text.trim())
        }
    }

    /**
     * Only the lines that can have changed are looked at: those since the
     * last look, the few before them, and any line that was still being
     * written then. A pass over the whole transcript for every finished
     * line of an evening's captions grew with it.
     */
    private fun scanStart(segments: List<TranscriptSegment>): Int {
        var start = maxOf(0, minOf(scannedCount, segments.size) - RECHECKED_LINES)
        firstOpenIndex?.let { start = minOf(start, it) }
        return minOf(start, segments.size)
    }

    private fun noteScanned(segments: List<TranscriptSegment>, start: Int) {
        scannedCount = segments.size
        // A line committed only provisionally (the stabilizer guessing the
        // engine went quiet for good) can still reopen and change after it
        // scrolled past the rechecked lines, so it stays in range the way
        // a line still open does, or its correction is never read out.
        val open = segments.subList(start, segments.size).indexOfFirst { !it.isCommitted || it.isProvisionalCommit }
        firstOpenIndex = if (open < 0) null else start + open
    }

    private fun forgetLinesNoLongerShown(segments: List<TranscriptSegment>) {
        if (segments.isEmpty() || segments.size < scannedCount) {
            scannedCount = 0
            firstOpenIndex = null
        }
        if (segments.isEmpty()) {
            announced.clear()
            lastSpeaker = null
            lastLine = null
        } else if (announced.size > segments.size) {
            val shown = segments.mapTo(HashSet()) { it.id }
            announced.keys.retainAll(shown)
        }
    }

    companion object {
        /** Finished lines this close to the end are looked at again, for a correction that comes after a line was read out. */
        const val RECHECKED_LINES = 8

        private fun grownTail(before: String?, now: String): String? {
            if (before.isNullOrBlank() || !now.startsWith(before)) return null
            if (now.length > before.length && !now[before.length].isWhitespace()) return null
            return now.substring(before.length).trim()
        }

        private fun words(text: String): String =
            if (!text.endsWith(CaptionStabilizer.CUT_OFF_MARK)) text else text.dropLast(CaptionStabilizer.CUT_OFF_MARK.length).trim()
    }
}
