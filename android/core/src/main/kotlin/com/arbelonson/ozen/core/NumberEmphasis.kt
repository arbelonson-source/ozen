package com.arbelonson.ozen.core

import java.text.BreakIterator
import java.util.Locale

/**
 * The text split into the characters a reader sees (grapheme clusters), with
 * the UTF-16 offset each one starts at, so every index below means what a
 * Swift string index meant.
 */
private class NumberEmphasisLine(val text: String) {
    val graphemes: List<String>
    val offsets: IntArray

    init {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val pieces = mutableListOf<String>()
        val starts = mutableListOf<Int>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            pieces.add(text.substring(start, end))
            starts.add(start)
            start = end
            end = iterator.next()
        }
        starts.add(text.length)
        graphemes = pieces
        offsets = starts.toIntArray()
    }

    val count: Int get() = graphemes.size

    fun slice(from: Int, to: Int): String = text.substring(offsets[from], offsets[to])
}

private class NumberEmphasisWord(
    val start: Int,
    val end: Int,
    val coreStart: Int,
    val coreEnd: Int,
    val core: String?,
) {
    val hasCore: Boolean get() = core != null
}

private class NumberEmphasisReading(val number: String, val prefixes: String)

private class NumberEmphasisJoined(val end: Int, val isFraction: Boolean)

private fun firstScalarOf(character: String): Int = character.codePointAt(0)

private fun isEmphasisNumber(character: String): Boolean = when (Character.getType(firstScalarOf(character)).toByte()) {
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
    else -> false
}

private fun isEmphasisLetter(character: String): Boolean = Character.isAlphabetic(firstScalarOf(character))

private fun isEmphasisWhitespace(character: String): Boolean = firstScalarOf(character).let {
    it in 0x09..0x0D || it == 0x20 || it == 0x85 || it == 0xA0 || it == 0x1680 || it in 0x2000..0x200A ||
        it == 0x2028 || it == 0x2029 || it == 0x202F || it == 0x205F || it == 0x3000
}

private fun emphasisCharacters(text: String): List<String> = NumberEmphasisLine(text).graphemes

private fun emphasisDropFirst(text: String, count: Int = 1): String = emphasisCharacters(text).drop(count).joinToString("")

private fun emphasisCount(text: String): Int = emphasisCharacters(text).size

private val emphasisGluedUnitSigns: Set<String> = setOf("%", "₪", "$")

/**
 * Where the numbers are in a caption line: the time of the appointment, how
 * many pills, a phone number, a price.
 *
 * Missing a word of small talk costs little; missing the "3" in "three times a
 * day" is what she would have to ask about again, and what she most needs to
 * have right afterwards. So numbers stand out on screen: written with digits
 * ("10:30", "050-1234567", "20%") or in words, with Hebrew's attached prefixes
 * ("u-veshesh" - "and at six", "lishlosha" - "to three").
 */
object NumberEmphasis {
    /**
     * The stretches of [text] that are numbers, in order, as ranges of UTF-16
     * offsets. Each covers a whole word (with its prefix), or for digits, from
     * the first digit to the last, with a percent sign right after; and the
     * unit that follows, when one does ("3 kadurim" - "3 pills", "chatzi
     * kadur" - "half a pill", "500 mg"), since the amount alone doesn't say
     * what of.
     */
    fun ranges(text: String): List<OpenEndRange<Int>> {
        val line = NumberEmphasisLine(text)
        val words = emphasisWords(line)
        val result = mutableListOf<OpenEndRange<Int>>()
        var lastJoined = -1
        for ((position, word) in words.withIndex()) {
            if (position <= lastJoined) continue
            val found = emphasisNumberRange(line, word, position, words) ?: continue
            // "3 kadurim" ("3 pills"), "shloshet riv'ei ha-kos" ("three quarters
            // of the cup"): what the amount is of joins it, unless punctuation
            // ends a word first ("be-sha'a 10:30, kadurim" - "at 10:30,
            // pills").
            var end = found.second
            var last = position
            var afterFraction = word.core?.let { emphasisReading(it) }?.let { it.number in fractionWords } ?: false
            var endsWord = found.second == word.end && !emphasisFollowedByComma(line, end)
            // Spoken numbers above ten are compound words: teens ("chamesh
            // esreh" - "fifteen"), tens and units ("esrim u-shlosha" -
            // "twenty-three"), and a following "va-chatzi" ("and a half") in a
            // time expression ("eser va-chatzi" - "half past ten"). Chain every
            // consecutive number word before looking for a unit, so the whole
            // compound stands out as one span instead of fragmenting into
            // disconnected pieces.
            while (endsWord && last + 1 < words.size) {
                val chained = emphasisChained(words[last + 1]) ?: break
                last += 1
                end = chained
                endsWord = end == words[last].end && !emphasisFollowedByComma(line, end)
            }
            while (endsWord && last + 1 < words.size) {
                val joined = emphasisJoinedWord(line, words[last + 1], allowingFraction = last == position, afterFraction = afterFraction)
                    ?: break
                last += 1
                end = joined.end
                endsWord = end == words[last].end && !emphasisFollowedByComma(line, end)
                if (!joined.isFraction) break
                afterFraction = true
            }
            result.add(IntRange(line.offsets[found.first], line.offsets[end] - 1))
            lastJoined = last
        }
        return result
    }

    /**
     * Whether a line is worth listing under "numbers said" in a saved
     * conversation: it has digits, or a counting word beyond one and two. Those
     * two are mostly idioms ("pa'am achat" - "once", "be-yom sheni" - "on
     * Monday"); a list of every line with them in it would be most of the
     * conversation.
     */
    fun hasListableNumber(text: String): Boolean = ranges(text).any { range ->
        val found = emphasisCharacters(text.substring(range.start, range.endExclusive))
        if (found.any { isEmphasisNumber(it) }) return@any true
        // The number is the first word; a unit may follow it.
        val first = found.takeWhile { !isEmphasisWhitespace(it) }.joinToString("")
        // Only ever emphasized as "once a day" and the like.
        if (first in onceWords) return@any true
        // One or two alone is mostly an idiom ("only once", "Monday"); with
        // more to it, it's a time or an amount ("eleven", "two pills").
        if (found.any { isEmphasisWhitespace(it) || it == "-" }) return@any true
        val reading = emphasisReading(first) ?: return@any false
        reading.number !in onesWords && reading.number !in twoWords
    }

    internal val prefixes: Set<String> = setOf("ו", "ב", "ל", "מ", "ה", "ש", "כ")

    /** What an amount is usually of, said right after it. */
    internal val units: Set<String> = setOf(
        "כדור", "כדורים", "טיפה", "טיפות", "כפית", "כפיות", "זריקה", "זריקות",
        "כף", "כפות", "כוס", "כוסות", "יחידה", "יחידות",
        "מ״ג", "מ\"ג", "מג", "מיליגרם", "מ״ל", "מ\"ל", "מל", "מיליליטר", "ליטר", "סמ״ק", "סמ\"ק",
        "גרם", "קילו", "ק״ג", "מטר", "קילומטר",
        // A tablet, capsule, inhaler puff or dose - as often taken as a
        // "kadur" ("pill"), but doctors say these too.
        "טבליה", "טבליות", "קפסולה", "קפסולות", "שאיפה", "שאיפות", "מנה", "מנות",
        // Whisper Turbo regularly writes dosages in Latin letters ("500 mg",
        // "10 ml"); matched case-insensitively in the unit lookup.
        "mg", "ml", "g", "kg", "cc", "cm",
        // A fever, read out in degrees.
        "מעלה", "מעלות",
        "שקל", "שקלים", "ש״ח", "ש\"ח", "שח", "דולר", "דולרים", "אחוז", "אחוזים",
        "שניות", "דקה", "דקות", "שעה", "שעות", "יום", "ימים", "שבוע", "שבועות", "חודש", "חודשים", "שנה", "שנים",
        "פעם", "פעמים",
    )

    internal val fractionsOf: Set<String> = setOf("רבעי")
    internal val fractionWords: Set<String> = setOf("חצי", "רבע", "שליש", "שלישים")
    internal val thisPeriodWords: Set<String> = setOf("היום", "השבוע", "החודש", "השנה")

    internal val onceWords: Set<String> = setOf("פעם", "ופעם")
    internal val periodWords: Set<String> = setOf(
        "יום", "שבוע", "חודש", "שנה", "שעה", "יומיים", "שבועיים", "חודשיים", "שנתיים", "שעתיים",
    )

    internal val onesWords: Set<String> = setOf("אחד", "אחת")
    internal val twoWords: Set<String> = setOf("שני", "שתי")
    internal val notACountBefore: Set<String> = setOf("אף", "ואף", "באף", "לאף", "כל", "וכל", "לכל", "בכל", "מכל", "בבת")
    internal val onHandWords: Set<String> = setOf("מצד", "ומצד")

    /** The second half of "each other": "echad le-sheni", "achat me-hashniya". */
    internal val otherOneWords: Set<String> = setOf(
        "השני", "לשני", "מהשני", "בשני", "והשני",
        "השנייה", "לשנייה", "מהשנייה", "בשנייה",
        "השניה", "לשניה", "מהשניה", "בשניה",
    )

    /**
     * Counting words only. "shanim" (years), "shavua" (a week) and ordinals
     * stay out, and so does anything that is mostly used as another word.
     */
    internal val numberWords: Set<String> = setOf(
        "אפס",
        "אחד", "אחת",
        "שניים", "שתיים", "שתים", "שני", "שתי",
        "שלוש", "שלושה", "שלושת",
        "ארבע", "ארבעה", "ארבעת",
        "חמש", "חמישה", "חמשת",
        "שש", "שישה", "ששה", "ששת",
        "שבע", "שבעה", "שבעת",
        "שמונה", "שמונת",
        "תשע", "תשעה", "תשעת",
        "עשר", "עשרה", "עשרת",
        "עשרים", "שלושים", "ארבעים", "חמישים", "שישים", "שבעים", "שמונים", "תשעים",
        "מאה", "מאתיים", "מאות",
        "אלף", "אלפיים", "אלפים",
        "מיליון",
        "חצי", "רבע", "שליש", "שלישים",
        // Two by themselves, and never anything else: "twice", "two days",
        // "two weeks". The times a pill is taken and the wait for the next
        // appointment are said this way.
        "פעמיים", "שעתיים", "יומיים", "שבועיים", "חודשיים", "שנתיים",
    )
}

/**
 * A unit after an amount, with or without the article ("chatzi ha-kos" -
 * "half the cup"), or right after a count, "riv'ei" ("quarters of"), which a
 * unit may follow in turn. On its own "riv'ei" is no amount: "riv'ei
 * ha-yare'ach" are the moon's quarters.
 */
private fun emphasisJoinedWord(
    line: NumberEmphasisLine,
    word: NumberEmphasisWord,
    allowingFraction: Boolean,
    afterFraction: Boolean,
): NumberEmphasisJoined? {
    // The shekel sign is a unit with no letters in it, so it has no `core` at
    // all: it needs its own check rather than the letters-only `units` lookup
    // below.
    if (line.slice(word.start, word.end) == "₪") return NumberEmphasisJoined(word.end, false)
    val core = word.core ?: return null
    if (allowingFraction && core in NumberEmphasis.fractionsOf) return NumberEmphasisJoined(word.coreEnd, true)
    // After a count "ha-yom" is "today" ("120/80 ha-yom"), as "ha-shavua" is
    // "this week": only a part of it is "the day" ("chatzi ha-yom").
    if (core in NumberEmphasis.thisPeriodWords && !afterFraction) return null
    val withoutArticle = if (core.startsWith("ה") && emphasisCount(core) > 2) emphasisDropFirst(core) else core
    val units = NumberEmphasis.units
    if (!(core in units || withoutArticle in units || core.lowercase(Locale.ROOT) in units)) return null
    return NumberEmphasisJoined(word.coreEnd, false)
}

/**
 * Whether [word] continues a compound number or time expression started by the
 * word before it: another number word on its own ("esreh" in "chamesh esreh" -
 * "fifteen"), or one with the "ו" ("and") conjunction ("u-shlosha",
 * "va-chatzi"). Any other prefix ("ba-shlosha" - "at three") means a new,
 * unrelated word, not a continuation. Gives the end of the number word.
 */
private fun emphasisChained(word: NumberEmphasisWord): Int? {
    val core = word.core ?: return null
    val reading = emphasisReading(core) ?: return null
    if (!(reading.prefixes.isEmpty() || reading.prefixes == "ו")) return null
    return word.coreEnd
}

private fun emphasisNumberRange(
    line: NumberEmphasisLine,
    word: NumberEmphasisWord,
    position: Int,
    words: List<NumberEmphasisWord>,
): Pair<Int, Int>? {
    val indices = word.start until word.end
    val firstDigit = indices.firstOrNull { isEmphasisNumber(line.graphemes[it]) }
    val lastDigit = indices.lastOrNull { isEmphasisNumber(line.graphemes[it]) }
    if (firstDigit != null && lastDigit != null) {
        var end = lastDigit + 1
        if (end < word.end && line.graphemes[end] in emphasisGluedUnitSigns) end += 1
        return firstDigit to end
    }
    val core = word.core ?: return null
    if (isOncePerPeriod(core, position, words)) return word.coreStart to word.coreEnd
    val reading = emphasisReading(core) ?: return null
    val previous = if (position > 0) words[position - 1].core else null
    val following = words.drop(position + 1).take(3).mapNotNull { it.core }
    if (previous != null && previous in NumberEmphasis.onHandWords &&
        (reading.number in NumberEmphasis.onesWords || reading.number in NumberEmphasis.twoWords)
    ) {
        // "mi-tzad echad ... mi-tzad sheni": "on the one hand ... on the other".
        return null
    }
    if (reading.number in NumberEmphasis.onesWords) {
        // "af echad" ("nobody") and "kol echad" ("everybody"), also after
        // "she-" ("she-kol echad" - "that everybody"), and "echad et ha-sheni"
        // ("each other", lit. "one to the other"): none of them a count of one.
        if (previous != null && isNotACountBefore(previous)) return null
        if (following.any { it in NumberEmphasis.otherOneWords }) return null
        // "echad ha-rof'im" ("one of the doctors"): one of a group. After an
        // hour it is still the time ("be-sha'a achat ha-yeladim").
        if (word.coreEnd < line.count && isEmphasisWhitespace(line.graphemes[word.coreEnd]) && position + 1 < words.size) {
            val next = words[position + 1].core
            if (next != null && isDefinitePlural(next) && !(previous?.let { isHour(it) } ?: false)) return null
        }
    }
    if (reading.number in NumberEmphasis.twoWords) {
        // "ha-sheni" ("the other one" / "the second") is never a count of two.
        if ("ה" in reading.prefixes) return null
        // "lishnei" after "echad" is "to each other"; alone it's "to two" or
        // "on Monday".
        val earlier = words.subList(maxOf(0, position - 3), position).mapNotNull { it.core }
        if (core in NumberEmphasis.otherOneWords && earlier.any { it in NumberEmphasis.onesWords }) return null
    }
    return word.coreStart to word.coreEnd
}

/**
 * "pa'am be-yom" ("once a day"), "pa'am be-shlosha yamim" ("once every three
 * days"): how often a pill is taken, which "pa'amayim" ("twice") already stood
 * out for. On its own "pa'am" is mostly another word: "af pa'am" ("never"),
 * "od pa'am ba-boker" ("again in the morning"), "pa'am hayiti" ("I once was").
 */
private fun isOncePerPeriod(core: String, position: Int, words: List<NumberEmphasisWord>): Boolean {
    if (core !in NumberEmphasis.onceWords || position + 1 >= words.size) return false
    val next = words[position + 1].core ?: return false
    if (!next.startsWith("ב") || emphasisCount(next) <= 2) return false
    if (position > 0) {
        val previous = words[position - 1].core
        if (previous != null && previous in NumberEmphasis.notACountBefore) return false
    }
    val period = emphasisDropFirst(next)
    return period in NumberEmphasis.periodWords || period in NumberEmphasis.numberWords
}

private fun emphasisWords(line: NumberEmphasisLine): List<NumberEmphasisWord> {
    val words = mutableListOf<NumberEmphasisWord>()
    var start: Int? = null
    var index = 0
    while (true) {
        val atEnd = index == line.count
        if (atEnd || emphasisSeparates(line, index)) {
            val wordStart = start
            if (wordStart != null) {
                val first = (wordStart until index).firstOrNull { isEmphasisLetter(line.graphemes[it]) }
                val last = (wordStart until index).lastOrNull { isEmphasisLetter(line.graphemes[it]) }
                if (first != null && last != null) {
                    val core = HebrewText.stripNiqqud(line.slice(first, last + 1))
                    words.add(NumberEmphasisWord(wordStart, index, first, last + 1, core))
                } else {
                    words.add(NumberEmphasisWord(wordStart, index, -1, -1, null))
                }
                start = null
            }
        } else if (start == null) {
            start = index
        }
        if (atEnd) break
        index += 1
    }
    return words
}

/**
 * Whitespace always ends a word. A hyphen, maqaf, dash or slash does too
 * ("shlosha-asar" - "thirteen", "be-3" - "in 3"), except between two digits,
 * where it is part of the number ("050-1234567", "3/4", "10:30-11:00").
 */
private fun emphasisSeparates(line: NumberEmphasisLine, index: Int): Boolean {
    val character = line.graphemes[index]
    if (isEmphasisWhitespace(character)) return true
    if (character == ",") return !isThousandsGroupComma(line, index)
    if (character.codePointCount(0, character.length) != 1 || character.codePointAt(0) !in HebrewText.wordJoiners) return false
    if (index == 0) return true
    val after = index + 1
    if (after >= line.count) return true
    return !(isEmphasisNumber(line.graphemes[index - 1]) && isEmphasisNumber(line.graphemes[after]))
}

/**
 * A comma right after a number, once it's no longer part of the word itself
 * (see [isThousandsGroupComma]), still ends it the way any other punctuation
 * does: "be-sha'a 10:30, kadurim" ("at 10:30, pills") isn't "10:30" pills.
 */
private fun emphasisFollowedByComma(line: NumberEmphasisLine, index: Int): Boolean =
    index < line.count && line.graphemes[index] == ","

/**
 * A comma joins one number only as a thousands group: a digit before it, and
 * exactly three digits after before the next non-digit ("1,234", "1,234,567" -
 * real grouping always has exactly three digits per group after the first).
 * Anything else - "1,2,3" (three pill counts), two phone numbers joined by a
 * bare comma - is more than one number, so the comma has to separate them
 * instead.
 */
private fun isThousandsGroupComma(line: NumberEmphasisLine, index: Int): Boolean {
    if (index == 0 || !isEmphasisNumber(line.graphemes[index - 1])) return false
    var cursor = index + 1
    var digitCount = 0
    while (cursor < line.count && isEmphasisNumber(line.graphemes[cursor]) && digitCount < 4) {
        digitCount += 1
        cursor += 1
    }
    return digitCount == 3
}

/**
 * The number word inside [word] and the prefixes attached in front of it (at
 * most two: vav + bet + "shesh" - "and" + "at" + "six"), or null when it isn't
 * one.
 */
private fun emphasisReading(word: String): NumberEmphasisReading? {
    if (word in NumberEmphasis.numberWords) return NumberEmphasisReading(word, "")
    val characters = emphasisCharacters(word)
    var restStart = 0
    repeat(2) {
        val first = characters.getOrNull(restStart)
        if (first == null || first !in NumberEmphasis.prefixes || characters.size - restStart <= 2) return null
        restStart += 1
        val rest = characters.drop(restStart).joinToString("")
        if (rest in NumberEmphasis.numberWords) {
            return NumberEmphasisReading(rest, characters.take(restStart).joinToString(""))
        }
    }
    return null
}

private fun isNotACountBefore(word: String): Boolean =
    word in NumberEmphasis.notACountBefore ||
        listOf("ש", "וש", "כש").any {
            word.startsWith(it) && emphasisDropFirst(word, emphasisCount(it)) in NumberEmphasis.notACountBefore
        }

private fun isDefinitePlural(word: String): Boolean =
    word.startsWith("ה") && emphasisCount(word) >= 5 && (word.endsWith("ים") || word.endsWith("ות"))

private fun isHour(word: String): Boolean =
    word == "שעה" || (emphasisCount(word) > 3 && emphasisDropFirst(word) == "שעה")
