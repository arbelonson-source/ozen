package com.arbelonson.ozen.core

import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/**
 * Shapes a caption line's text for reading at large sizes.
 *
 * One utterance can hold up to half a minute of someone talking, which at
 * 30 to 60 points is a wall of text where the eye loses its place. Breaking
 * it into short paragraphs at sentence ends gives the reader somewhere to
 * rest and find the newest words. Short lines are left alone, and nothing
 * is ever dropped or reordered: only a space after sentence-ending
 * punctuation can become a line break.
 */
object CaptionLayout {
    /** Sentences are gathered until a paragraph would pass this length. */
    const val PARAGRAPH_CHARACTERS = 90

    fun readableText(text: String, paragraphCharacters: Int = PARAGRAPH_CHARACTERS): String {
        val sentences = splitSentences(text)
        if (sentences.size <= 1 || graphemeCountOf(text) <= paragraphCharacters) return text

        val paragraphs = ArrayList<String>()
        var current = ""
        for (sentence in sentences) {
            if (current.isEmpty()) {
                current = sentence
            } else if (graphemeCountOf(current) + 1 + graphemeCountOf(sentence) <= paragraphCharacters) {
                current += " $sentence"
            } else {
                paragraphs.add(current)
                current = sentence
            }
        }
        if (current.isNotEmpty()) paragraphs.add(current)
        return paragraphs.joinToString("\n")
    }

    /**
     * [readableText], ready to draw: in a right-to-left language each
     * paragraph with a right-to-left letter in it starts with an invisible
     * right-to-left mark.
     *
     * A paragraph takes its reading direction from its first letter. "OK, az
     * nitra'e machar" ("OK, see you tomorrow") starts with a Latin one, so it
     * was laid out left to right, and read from the right it came out as "az
     * nitra'e machar" followed by "OK": the words in the wrong order. Same for
     * a line that opens with a name like "WhatsApp". The mark makes Hebrew the
     * paragraph's direction whatever word comes first. For display only:
     * copied and shared text gets only the number isolates ([copiedText]),
     * and spoken text stays as recognized.
     */
    fun displayText(text: String, languageCode: String = "he"): String =
        directed(readableText(text), languageCode)

    /**
     * The marks alone, without breaking the text into paragraphs: for a
     * one- or two-line preview.
     */
    fun directed(text: String, languageCode: String = "he"): String {
        if (!isRightToLeft(languageCode)) return text
        return isolatingNumbers(text)
            .split("\n")
            .joinToString("\n") { paragraph ->
                // English said to her has no Hebrew to read from the right:
                // forced right to left, "how did you sleep?" was drawn
                // "?how did you sleep", its question mark at the start.
                if (hasOnlyLeftToRightLetters(paragraph)) paragraph else RIGHT_TO_LEFT_MARK + anchorTrailingPunctuation(paragraph)
            }
    }

    /**
     * Whether [text] has letters and none of them is a right-to-left one.
     * A line of only digits and punctuation has none, and stays right to
     * left like the Hebrew around it.
     */
    internal fun hasOnlyLeftToRightLetters(text: String): Boolean {
        var hasLetter = false
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isAlphabetic(codePoint)) {
                if (TextDirection.isRightToLeftLetter(codePoint)) return false
                hasLetter = true
            }
            index += Character.charCount(codePoint)
        }
        return hasLetter
    }

    internal const val RIGHT_TO_LEFT_MARK: String = TextDirection.RIGHT_TO_LEFT_MARK

    /**
     * A line copied to paste into a chat: the words as recognized, with
     * only its phone numbers and star codes isolated, as in a shared
     * conversation, so a Hebrew chat doesn't turn "050 123 4567" around.
     */
    fun copiedText(text: String): String = isolatingNumbers(text)

    /**
     * A number is read left to right inside Hebrew too, but the spaces,
     * "*" and "+" in "050 123 4567", "*2700" or "+972-3-1234567" have no
     * direction of their own, and a right-to-left line drew them "4567
     * 123 050", "2700*" and "972-3-1234567+" (checked with fribidi).
     * Each phone number and star code is kept whole, left to right, in an
     * isolate: invisible, and the digits and what they dial are the same.
     */
    internal fun isolatingNumbers(text: String): String {
        val stars = ArrayList<IntRange>()
        val found = starCode.matcher(text)
        while (found.find()) stars.add(found.start() until found.end())
        val ranges = (PhoneNumbers.matches(text).map { it.start until it.end } + stars).sortedBy { it.first }
        if (ranges.isEmpty()) return text
        val result = StringBuilder()
        var rest = 0
        for (range in ranges) {
            if (range.first < rest) continue
            result.append(text, rest, range.first).append('⁦').append(text, range.first, range.last + 1).append('⁩')
            rest = range.last + 1
        }
        return result.append(text, rest, text.length).toString()
    }

    private val starCode: Pattern = Pattern.compile("(?<![\\d*])\\*\\d{2,6}(?!\\d)", Pattern.UNICODE_CHARACTER_CLASS)

    private val neutralEnders = setOf(".", "!", "?", ")", ":")

    /**
     * Neutral punctuation ending a right-to-left paragraph (UAX #9) takes
     * its direction from the run before it. After a Latin word or a
     * digit ("WhatsApp.", "Acamol!") that resolves left to right, so the
     * mark visually jumps to the wrong side of those characters instead
     * of staying at the line's true end. A trailing mark, flanking the
     * punctuation with right-to-left context on both sides, anchors it
     * where it belongs.
     */
    private fun anchorTrailingPunctuation(line: String): String {
        val characters = graphemesOf(line)
        val last = characters.lastOrNull() ?: return line
        if (last !in neutralEnders) return line
        var beforeMarks = characters.size
        while (beforeMarks > 0 && characters[beforeMarks - 1] in neutralEnders) beforeMarks -= 1
        if (beforeMarks <= 0) return line
        val character = characters[beforeMarks - 1]
        val scalar = character.codePointAt(0)
        val isLetterOrNumber = Character.isAlphabetic(scalar) || when (Character.getType(scalar).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
        if (!isLetterOrNumber || TextDirection.isRightToLeftLetter(scalar)) return line
        return line + RIGHT_TO_LEFT_MARK
    }

    /**
     * Whether [text] would be laid out left to right on its own: its first
     * letter (skipping digits, spaces and punctuation, which have no
     * direction of their own) isn't a right-to-left one.
     */
    internal fun opensLeftToRight(text: String): Boolean = TextDirection.opensLeftToRight(text)

    internal fun isRightToLeft(languageCode: String): Boolean = TextDirection.isRightToLeft(languageCode)

    private val enders = setOf(".", "?", "!", "…")
    private val closers = setOf("\"", "'", "”", "’", ")", "]", "״", "׳")

    /**
     * Splits after ".", "?", "!" or "…" (and any closing quote or bracket
     * right after it) when whitespace follows. "3.5" and "d\"r" ("doctor",
     * using a gershayim instead of a period) never split, because no space
     * follows the dot or the gershayim.
     */
    internal fun splitSentences(text: String): List<String> {
        val characters = graphemesOf(text)
        val sentences = ArrayList<String>()
        var current = StringBuilder()
        var index = 0
        while (index < characters.size) {
            val character = characters[index]
            current.append(character)
            var next = index + 1
            if (character in enders) {
                while (next < characters.size && (characters[next] in enders || characters[next] in closers)) {
                    current.append(characters[next])
                    next += 1
                }
                if (next < characters.size && isWhitespaceCharacter(characters[next])) {
                    val trimmed = trimmingSpaces(current.toString())
                    if (trimmed.isNotEmpty()) sentences.add(trimmed)
                    current = StringBuilder()
                    while (next < characters.size && isWhitespaceCharacter(characters[next])) next += 1
                }
            }
            index = next
        }
        val tail = trimmingSpaces(current.toString())
        if (tail.isNotEmpty()) sentences.add(tail)
        return sentences
    }

    /**
     * How many of the newest lines the caption screen draws. A phone left
     * listening on the nightstand for days collects thousands, and every
     * word arriving made the screen go over all of them again. Nobody
     * scrolls back through hundreds of lines on the live screen; older
     * ones are in the saved conversations.
     */
    const val ON_SCREEN_LINE_LIMIT = 300

    /** Where the lines drawn on the caption screen start. */
    fun firstOnScreenIndex(lineCount: Int): Int = maxOf(0, lineCount - ON_SCREEN_LINE_LIMIT)

    /**
     * Whether a line shows its speaker's name above it.
     *
     * Like a chat, the name appears when the speaker changes, not on every
     * line: a run of lines by one person reads as one block and leaves more of
     * the screen for words. A line with no identified speaker shows no label
     * at all rather than "dover lo yadu'a" ("unknown speaker") on every row.
     */
    fun showsSpeakerLabel(segment: TranscriptSegment, previous: TranscriptSegment?): Boolean {
        val cluster = segment.speakerClusterID ?: return false
        // After a quiet stretch the time is drawn between the lines, and
        // the name heads the new run again.
        return previous?.speakerClusterID != cluster || startsAfterQuiet(segment, previous)
    }

    /**
     * The same, with [name] giving the name each line shows. Someone
     * recorded several times has one voice per recording, and her lines
     * move between them: compared by voice, her name headed almost every
     * line.
     */
    fun showsSpeakerLabel(segment: TranscriptSegment, previous: TranscriptSegment?, name: (TranscriptSegment) -> String): Boolean {
        if (!showsSpeakerLabel(segment, previous)) return false
        if (previous == null || previous.speakerClusterID == null || startsAfterQuiet(segment, previous)) return true
        return name(previous) != name(segment)
    }

    /**
     * A quiet stretch this long between two lines puts the later line's
     * clock time between them, so a sentence from half an hour ago isn't
     * read as the one before the words just said.
     */
    const val QUIET_GAP_SECONDS: Double = 5.0 * 60

    /** Whether [segment] began [QUIET_GAP_SECONDS] or more after [previous] last changed. */
    fun startsAfterQuiet(segment: TranscriptSegment, previous: TranscriptSegment?): Boolean {
        if (previous == null) return false
        return segment.startTimestamp - previous.lastUpdateTimestamp >= QUIET_GAP_SECONDS
    }

    /**
     * The same rule for a saved conversation, where each line carries the
     * name it had when it was saved. An unknown-speaker name counts as no
     * name, so it isn't repeated down the page.
     */
    fun showsSpeakerLabel(segment: SavedSegment, previous: SavedSegment?): Boolean {
        val name = labelName(segment) ?: return false
        return (previous?.let { labelName(it) }) != name || startsAfterQuiet(segment, previous)
    }

    /**
     * The saved-history version of [startsAfterQuiet]: a [SavedSegment] only
     * ever kept its start time, not the live segment's last-update time, so
     * the gap is measured between the two start times.
     */
    fun startsAfterQuiet(segment: SavedSegment, previous: SavedSegment?): Boolean {
        if (previous == null) return false
        return segment.startTimestamp - previous.startTimestamp >= QUIET_GAP_SECONDS
    }

    private fun labelName(segment: SavedSegment): String? {
        val name = segment.speakerName?.let { trimmingWhitespaceAndNewlines(it) } ?: return null
        if (name.isEmpty() || TranscriptSessionSummary.isUnknownSpeakerLabel(name)) return null
        return name
    }

    /**
     * The lines of a saved conversation that carry a clock time: the first
     * one, then the first line at least [interval] after the last time
     * shown. Enough to answer "when did the doctor say that" without a
     * timestamp cluttering every line.
     */
    fun timeMarkedLineIDs(segments: List<SavedSegment>, interval: Double = 300.0): Set<UUID> {
        val marked = HashSet<UUID>()
        var lastShown: Double? = null
        for (segment in segments) {
            val last = lastShown
            if (last != null && segment.startTimestamp - last < interval) continue
            marked.add(segment.id)
            lastShown = segment.startTimestamp
        }
        return marked
    }
}

private fun graphemesOf(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return result
}

private fun graphemeCountOf(text: String): Int = graphemesOf(text).size

private fun isLayoutWhitespace(codePoint: Int): Boolean =
    codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
        codePoint in 0x2000..0x200A || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
        codePoint == 0x205F || codePoint == 0x3000

private fun isWhitespaceCharacter(character: String): Boolean = isLayoutWhitespace(character.codePointAt(0))

private fun trimmingSpaces(text: String): String =
    trimmingLayoutScalars(text) { Character.getType(it).toByte() == Character.SPACE_SEPARATOR || it == 0x09 }

private fun trimmingWhitespaceAndNewlines(text: String): String =
    trimmingLayoutScalars(text) {
        when (Character.getType(it).toByte()) {
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> it in 0x09..0x0D || it == 0x85
        }
    }

private inline fun trimmingLayoutScalars(text: String, isTrimmed: (Int) -> Boolean): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    while (from < to && isTrimmed(scalars[from])) from++
    while (to > from && isTrimmed(scalars[to - 1])) to--
    return String(scalars, from, to - from)
}
