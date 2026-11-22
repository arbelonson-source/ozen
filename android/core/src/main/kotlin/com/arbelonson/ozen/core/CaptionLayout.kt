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
        // Counted before it is split: the line still being said is shaped
        // again at every word, and a short one stays as it is however many
        // sentences it has.
        if (graphemeCountOf(text) <= paragraphCharacters) return text
        val sentences = splitSentences(text)
        if (sentences.size <= 1) return text

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
    fun displayText(text: String, languageCode: String): String =
        directed(joiningNumbers(readableText(text), languageCode), languageCode)

    /**
     * Spaces inside a phone number, a dose, or between an amount and what
     * it counts ("3 pills") become no-break spaces so a caption line never
     * wraps in the middle of one, and a phone number's hyphens become
     * non-breaking hyphens: "050-1234567" wrapped after "050-". A longer
     * run of number words still wraps at its spaces: held as one, it would
     * not fit a line at the largest text size. Same as the iPhone, same
     * length, so offsets stay valid. Display only.
     */
    internal fun joiningNumbers(text: String, languageCode: String): String {
        val phones = PhoneNumbers.matches(text)
        val amounts = NumberEmphasis.ranges(text, languageCode = languageCode)
        // Copied only once something changes: most lines have no number in
        // them, and the line still being said comes here at every word.
        // A change only turns a space or a hyphen into its joining form, and
        // making it twice is making it once, so [text] says where they are.
        var result: StringBuilder? = null
        fun joined(index: Int, character: Char) {
            val copy = result ?: StringBuilder(text)
            copy.setCharAt(index, character)
            result = copy
        }
        for (phone in phones) {
            for (index in phone.start until phone.end) if (text[index] == ' ') joined(index, NO_BREAK_SPACE)
        }
        for (amount in amounts) {
            val start = amount.start
            val end = amount.endExclusive
            if (spacesIn(text, start, end) != 1) continue
            for (index in start until end) if (text[index] == ' ') joined(index, NO_BREAK_SPACE)
        }
        for (phone in phones) {
            for (index in phone.start until phone.end) if (text[index] == '-') joined(index, NON_BREAKING_HYPHEN)
        }
        val changed = result
        val dose = doseSpace.matcher(changed ?: text)
        if (!dose.find()) return changed?.toString() ?: text
        return dose.replaceAll(NO_BREAK_SPACE.toString())
    }

    private fun spacesIn(text: String, start: Int, end: Int): Int {
        var spaces = 0
        for (index in start until end) if (text[index] == ' ') spaces++
        return spaces
    }

    private const val NO_BREAK_SPACE = '\u00A0'
    private const val NON_BREAKING_HYPHEN = '\u2011'

    private val doseSpace = unicodePattern("""(?<=\d) (?=(?:מיליגרם|מיליליטר|מ״ג|מ״ל|גרם|ליטר|mg|ml)(?![\p{L}]))""")

    /**
     * The marks alone, without breaking the text into paragraphs: for a
     * one- or two-line preview.
     */
    fun directed(text: String, languageCode: String): String {
        if (!isRightToLeft(languageCode)) return text
        val isolated = isolatingNumbers(text)
        if ('\n' !in isolated) return directedParagraph(isolated)
        return isolated.split("\n").joinToString("\n") { directedParagraph(it) }
    }

    // English said to her has no Hebrew to read from the right: forced
    // right to left, "how did you sleep?" was drawn "?how did you sleep",
    // its question mark at the start.
    private fun directedParagraph(paragraph: String): String =
        if (hasOnlyLeftToRightLetters(paragraph)) paragraph else RIGHT_TO_LEFT_MARK + anchorTrailingPunctuation(paragraph)

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
    fun copiedText(text: String): String = marked(isolatingNumbers(text))

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

    private val starCode: Pattern = unicodePattern("(?<![\\d*])\\*\\d{2,6}(?!\\d)")

    private const val NEUTRAL_ENDERS = ".!?):"

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
        val characters = Graphemes.of(line)
        if (characters.count == 0 || !characters.isOneOf(characters.count - 1, NEUTRAL_ENDERS)) return line
        var beforeMarks = characters.count
        while (beforeMarks > 0 && characters.isOneOf(beforeMarks - 1, NEUTRAL_ENDERS)) beforeMarks -= 1
        if (beforeMarks <= 0) return line
        val scalar = characters.firstScalar(beforeMarks - 1)
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

    internal fun marked(text: String): String = TextDirection.marked(text)

    internal fun isRightToLeft(languageCode: String): Boolean = TextDirection.isRightToLeft(languageCode)

    /**
     * Whether a caption line reads from the right: one with a right-to-left
     * letter does, one with only left-to-right letters doesn't, and one with
     * no letters at all (a time, a number) follows the spoken language.
     */
    fun readsRightToLeft(text: String, languageCode: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (TextDirection.isRightToLeftLetter(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        if (hasOnlyLeftToRightLetters(text)) return false
        return isRightToLeft(languageCode)
    }

    private const val ENDERS = ".?!…"
    private const val CLOSERS = "\"'”’)]״׳"

    /**
     * Splits after ".", "?", "!" or "…" (and any closing quote or bracket
     * right after it) when whitespace follows. "3.5" and "d\"r" ("doctor",
     * using a gershayim instead of a period) never split, because no space
     * follows the dot or the gershayim.
     */
    internal fun splitSentences(text: String): List<String> {
        val characters = Graphemes.of(text)
        val count = characters.count
        val sentences = ArrayList<String>()
        // A sentence is the text from [from] to where it ends: every
        // character after the last cut up to there belongs to it.
        var from = 0
        var index = 0
        while (index < count) {
            var next = index + 1
            if (characters.isOneOf(index, ENDERS)) {
                while (next < count && (characters.isOneOf(next, ENDERS) || characters.isOneOf(next, CLOSERS))) next += 1
                if (next < count && isLayoutWhitespace(characters.firstScalar(next))) {
                    val trimmed = trimmingSpaces(text.substring(from, characters.start(next)))
                    if (trimmed.isNotEmpty()) sentences.add(trimmed)
                    while (next < count && isLayoutWhitespace(characters.firstScalar(next))) next += 1
                    from = characters.start(next)
                }
            }
            index = next
        }
        val tail = trimmingSpaces(text.substring(from))
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
     * Where the drawn lines start while she reads back, [heldAt] being where
     * they started when she scrolled up (null at the newest line), as on the
     * iPhone. Each new line used to take the top one away, and the line she
     * was reading went once it was the oldest drawn. Held, nothing above her
     * goes; once twice [limit] are drawn, the oldest go again.
     */
    fun firstOnScreenIndex(lineCount: Int, heldAt: Int?, limit: Int = ON_SCREEN_LINE_LIMIT): Int {
        val usual = maxOf(0, lineCount - limit)
        if (heldAt == null) return usual
        return maxOf(minOf(heldAt, usual), lineCount - 2 * limit)
    }

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

private fun graphemeCountOf(text: String): Int = Graphemes.of(text).count

private fun isLayoutWhitespace(codePoint: Int): Boolean =
    codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
        codePoint in 0x2000..0x200A || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
        codePoint == 0x205F || codePoint == 0x3000

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
    var from = 0
    while (from < text.length) {
        val scalar = text.codePointAt(from)
        if (!isTrimmed(scalar)) break
        from += Character.charCount(scalar)
    }
    var to = text.length
    while (to > from) {
        val scalar = text.codePointBefore(to)
        if (!isTrimmed(scalar)) break
        to -= Character.charCount(scalar)
    }
    return text.substring(from, to)
}

/**
 * The characters a reader sees in [text] (grapheme clusters), by where
 * each one starts.
 *
 * Splitting a line made a new string for each of its characters, and the
 * line still being said is split several times at every word: with the
 * rest of its layout, a line of 60 Hebrew words made about 300 KB a word.
 * Most caption text has no character of more than one UTF-16 unit, and
 * none that joins the one next to it ([standsAlone]): such a text is split
 * at every unit without asking a BreakIterator, and its characters are
 * made into strings only when asked for, most of them shared ([unit]). Any
 * other text (a vowel point, an accent mark, an emoji, CR LF) is split by
 * the BreakIterator as before.
 */
internal class Graphemes private constructor(val text: String, private val starts: IntArray?) {
    val count: Int = if (starts == null) text.length else starts.size - 1

    /** Where character [index] starts; [count] gives the end of the text. */
    fun start(index: Int): Int = if (starts == null) index else starts[index]

    operator fun get(index: Int): String = if (starts == null) unit(text[index]) else text.substring(starts[index], starts[index + 1])

    /** Whether character [index] is one unit, and one of [units]. */
    fun isOneOf(index: Int, units: String): Boolean {
        val start = start(index)
        return start(index + 1) - start == 1 && units.indexOf(text[start]) >= 0
    }

    fun firstScalar(index: Int): Int = text.codePointAt(start(index))

    /** Every character as a string, in order: made at once, or for a text split at every unit, each when it is read. */
    fun strings(): List<String> = if (starts == null) {
        object : AbstractList<String>() {
            override val size: Int get() = text.length

            override fun get(index: Int): String = unit(text[index])
        }
    } else {
        List(count) { get(it) }
    }

    companion object {
        fun of(text: String): Graphemes = Graphemes(text, if (splitsAtEveryUnit(text)) null else byBreakIterator(text))

        fun splitsAtEveryUnit(text: String): Boolean {
            for (unit in text) if (!standsAlone(unit)) return false
            return true
        }

        /** Where each character starts, then the end of the text, as the BreakIterator finds them. */
        fun byBreakIterator(text: String): IntArray {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
            iterator.setText(text)
            val boundaries = IntArray(text.length + 1)
            var count = 0
            var boundary = iterator.first()
            while (boundary != BreakIterator.DONE) {
                boundaries[count++] = boundary
                boundary = iterator.next()
            }
            return boundaries.copyOf(count)
        }

        /**
         * Whether [unit] is a character by itself whatever stands next to
         * it: a letter, digit, space or punctuation mark of Latin, Hebrew,
         * Arabic, Cyrillic or Chinese text, a control, or a direction mark,
         * none of which a grapheme rule joins to a neighbour. Left out are
         * the vowel points, accent and other combining marks, the joiners,
         * the Arabic signs that join the next letter, surrogates (emoji),
         * CR (one with a LF after it) and Hangul: text with any of them
         * goes to the BreakIterator.
         */
        fun standsAlone(unit: Char): Boolean {
            val code = unit.code
            return when {
                code < 0x0300 -> code != 0x0D
                code in 0x0400..0x0482 || code in 0x048A..0x052F -> true
                code in 0x05D0..0x05EA || code in 0x05EF..0x05F4 -> true
                code == 0x05BE || code == 0x05C0 || code == 0x05C3 || code == 0x05C6 -> true
                code in 0x0600..0x06FF -> when (Character.getType(unit).toByte()) {
                    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                    Character.FORMAT, Character.UNASSIGNED,
                    -> false
                    else -> true
                }
                code in 0x2000..0x206F -> code != 0x200C && code != 0x200D && code != 0x2065
                code in 0x20A0..0x20BF -> true
                code in 0x3000..0x3029 || code in 0x4E00..0x9FFF || code in 0xFF01..0xFF5E -> true
                else -> false
            }
        }

        private const val SHARED_BELOW = 0x3000

        private val units = arrayOfNulls<Array<String?>>(SHARED_BELOW ushr 8)

        /**
         * [unit] as a string. Below [SHARED_BELOW] (Latin, Hebrew, Arabic,
         * Cyrillic, punctuation) it is the same one each time, made when it
         * is first asked for; two threads asking at once may each make one,
         * and either is the same text. A Chinese character is made each
         * time, so thousands of them are not kept for good.
         */
        fun unit(unit: Char): String {
            if (unit.code >= SHARED_BELOW) return unit.toString()
            val pageAt = unit.code ushr 8
            val page = units[pageAt] ?: arrayOfNulls<String>(256).also { units[pageAt] = it }
            return page[unit.code and 0xFF] ?: unit.toString().also { page[unit.code and 0xFF] = it }
        }
    }
}
