package com.arbelonson.ozen.core

import java.util.Locale

/**
 * The text split into the characters a reader sees (grapheme clusters), with
 * the UTF-16 offset each one starts at, so every index below means what a
 * Swift string index meant.
 */
private class NumberEmphasisLine(val text: String) {
    private val characters = Graphemes.of(text)
    val graphemes: List<String> = characters.strings()

    val count: Int get() = characters.count

    /** Where character [index] starts; [count] gives the end of the text. */
    fun offset(index: Int): Int = characters.start(index)

    fun slice(from: Int, to: Int): String = text.substring(offset(from), offset(to))
}

/** The first character from [from] until [to] that [matches] accepts, or -1. */
private inline fun NumberEmphasisLine.firstAt(from: Int, to: Int, matches: (String) -> Boolean): Int {
    for (index in from until to) if (matches(graphemes[index])) return index
    return -1
}

/** The last character from [from] until [to] that [matches] accepts, or -1. */
private inline fun NumberEmphasisLine.lastAt(from: Int, to: Int, matches: (String) -> Boolean): Int {
    var index = to - 1
    while (index >= from) {
        if (matches(graphemes[index])) return index
        index -= 1
    }
    return -1
}

/**
 * The words of [line]: word number `i` runs from character [start] until
 * [end], its letters from [coreStart] until [coreEnd] (both -1 when it has
 * none), the four kept side by side in [bounds].
 *
 * The line still being said is looked through again at every word, twice:
 * for how it is drawn and for which of its words stand out. An object for
 * each of its words, and each word's letters cut out of it, made about
 * 7 KB at every word of a 100-word Hebrew line, and 12 KB of an English
 * one, whose words were each made lower case as well. A word's letters are
 * now cut out only when asked for ([core]), and a line with no word that
 * could be a number has no words made at all ([emphasisWords]).
 */
private class NumberEmphasisWords(private val line: NumberEmphasisLine, private val bounds: IntArray) {
    val size: Int get() = bounds.size / 4

    fun start(word: Int): Int = bounds[4 * word]

    fun end(word: Int): Int = bounds[4 * word + 1]

    fun coreStart(word: Int): Int = bounds[4 * word + 2]

    fun coreEnd(word: Int): Int = bounds[4 * word + 3]

    fun hasCore(word: Int): Boolean = coreStart(word) >= 0

    /** The word's letters without vowel points, or null when it has none. */
    fun core(word: Int): String? = if (hasCore(word)) HebrewText.stripNiqqud(line.slice(coreStart(word), coreEnd(word))) else null

    /** [mayCount] for the word. */
    fun mayCount(word: Int): Boolean = hasCore(word) && line.mayCount(coreStart(word), coreEnd(word))

    /** [NumberWordProfile.mayStartANumber] for the word's letters, before they are cut out and made lower case. */
    fun mayStartANumber(word: Int, profile: NumberWordProfile): Boolean =
        hasCore(word) && profile.mayStartANumber(line.text, line.offset(coreStart(word)), line.offset(coreEnd(word)))
}

/**
 * False when the letters of the line from character [coreStart] until
 * [coreEnd] are no counting word and no "pa'am" ("once"), with or without
 * prefixes ([readsAsACount]). Vowel points would change them, so a word
 * with any is cut out to be asked.
 */
private fun NumberEmphasisLine.mayCount(coreStart: Int, coreEnd: Int): Boolean {
    val from = offset(coreStart)
    val to = offset(coreEnd)
    for (index in from until to) {
        if (text[index] in '\u0591'..'\u05C7') return HebrewText.stripNiqqud(text.substring(from, to)).let { readsAsACount(it, 0, it.length) }
    }
    return readsAsACount(text, from, to)
}

private fun lastTwoUnits(text: String, end: Int): Int = (text[end - 2].code shl 16) or text[end - 1].code

/**
 * Whether the part of [text] from [from] until [to] is a counting word or
 * "pa'am" with at most two letters in front of it: a prefix only goes in
 * front, and a word with more than two is neither. Its last two units
 * ([NumberEmphasis.countEndings]) leave out most words at once.
 */
private fun readsAsACount(text: String, from: Int, to: Int): Boolean {
    if (to - from < 2 || NumberEmphasis.countEndings.binarySearch(lastTwoUnits(text, to)) < 0) return false
    for (word in NumberEmphasis.countWords) {
        if (to - from - word.length in 0..2 && text.regionMatches(to - word.length, word, 0, word.length)) return true
    }
    return false
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

private fun emphasisCharacters(text: String): List<String> = Graphemes.of(text).strings()

private fun emphasisDropFirst(text: String, count: Int = 1): String {
    val characters = Graphemes.of(text)
    return if (count >= characters.count) "" else text.substring(characters.start(count))
}

private fun emphasisCount(text: String): Int = Graphemes.of(text).count

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
    fun ranges(text: String, languageCode: String, decimalComma: Boolean = false): List<OpenEndRange<Int>> {
        if (UILanguage.match(languageCode) == UILanguage.ChineseSimplified) return ChineseNumberEmphasis.ranges(text)
        // Both passes split the same words, so they are split once.
        val line = NumberEmphasisLine(text)
        val profile = NumberWordProfile.profile(languageCode)
        val words = emphasisWords(line, decimalComma, profile)
        if (profile == null) return hebrewRanges(line, words, decimalComma)
        val merged = mutableListOf<OpenEndRange<Int>>()
        for (range in (spelledRanges(line, words, decimalComma, profile) + hebrewRanges(line, words, decimalComma)).sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && range.start < last.endExclusive) {
                merged[merged.size - 1] = last.start until maxOf(last.endExclusive, range.endExclusive)
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    private fun hebrewRanges(text: String, decimalComma: Boolean): List<OpenEndRange<Int>> {
        val line = NumberEmphasisLine(text)
        return hebrewRanges(line, emphasisWords(line, decimalComma, null), decimalComma)
    }

    private fun hebrewRanges(line: NumberEmphasisLine, words: NumberEmphasisWords, decimalComma: Boolean): List<OpenEndRange<Int>> {
        val result = mutableListOf<OpenEndRange<Int>>()
        var lastJoined = -1
        for (position in 0 until words.size) {
            if (position <= lastJoined) continue
            val found = emphasisNumberRange(line, words, position) ?: continue
            // "3 kadurim" ("3 pills"), "shloshet riv'ei ha-kos" ("three quarters
            // of the cup"): what the amount is of joins it, unless punctuation
            // ends a word first ("be-sha'a 10:30, kadurim" - "at 10:30,
            // pills").
            var end = found.second
            var last = position
            var afterFraction = words.core(position)?.let { emphasisReading(it) }?.let { it.number in fractionWords } ?: false
            var endsWord = found.second == words.end(position) && !emphasisFollowedByComma(line, end, decimalComma)
            // Spoken numbers above ten are compound words: teens ("chamesh
            // esreh" - "fifteen"), tens and units ("esrim u-shlosha" -
            // "twenty-three"), and a following "va-chatzi" ("and a half") in a
            // time expression ("eser va-chatzi" - "half past ten"). Chain every
            // consecutive number word before looking for a unit, so the whole
            // compound stands out as one span instead of fragmenting into
            // disconnected pieces.
            while (endsWord && last + 1 < words.size) {
                val chained = emphasisChained(words, last + 1) ?: break
                last += 1
                end = chained
                endsWord = end == words.end(last) && !emphasisFollowedByComma(line, end, decimalComma)
            }
            while (endsWord && last + 1 < words.size) {
                val joined = emphasisJoinedWord(line, words, last + 1, allowingFraction = last == position, afterFraction = afterFraction)
                    ?: break
                last += 1
                end = joined.end
                endsWord = end == words.end(last) && !emphasisFollowedByComma(line, end, decimalComma)
                if (!joined.isFraction) break
                afterFraction = true
            }
            result.add(IntRange(line.offset(found.first), line.offset(end) - 1))
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
    fun hasListableNumber(text: String, languageCode: String, decimalComma: Boolean = false): Boolean {
        if (UILanguage.match(languageCode) == UILanguage.ChineseSimplified) return ChineseNumberEmphasis.hasListableNumber(text)
        val profile = NumberWordProfile.profile(languageCode)
        if (profile != null && spelledListable(text, decimalComma, profile)) return true
        return hasHebrewListableNumber(text, decimalComma)
    }

    private fun hasHebrewListableNumber(text: String, decimalComma: Boolean): Boolean = hebrewRanges(text, decimalComma).any { range ->
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

    /**
     * Whether [word] is a counting word, with or without prefixes attached.
     * "hasheni" ("the second", "the other one") reads as "sheni" with a
     * prefix, but it is an ordinal, so it isn't one.
     */
    internal fun isNumberWord(word: String): Boolean = emphasisReading(word) != null && word !in otherOneWords

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

    /** [numberWords] and [onceWords], each at least two units long, for [readsAsACount]. */
    internal val countWords: Array<String> = (numberWords + onceWords).toTypedArray()

    /**
     * The last two units of each of [countWords], sorted, for
     * [readsAsACount]: a prefix only goes in front, so a word ending
     * otherwise is none of them.
     */
    internal val countEndings: IntArray = countWords.map { lastTwoUnits(it, it.length) }.distinct().sorted().toIntArray()
}

/**
 * A unit after an amount, with or without the article ("chatzi ha-kos" -
 * "half the cup"), or right after a count, "riv'ei" ("quarters of"), which a
 * unit may follow in turn. On its own "riv'ei" is no amount: "riv'ei
 * ha-yare'ach" are the moon's quarters.
 */
private fun emphasisJoinedWord(
    line: NumberEmphasisLine,
    words: NumberEmphasisWords,
    word: Int,
    allowingFraction: Boolean,
    afterFraction: Boolean,
): NumberEmphasisJoined? {
    // The shekel sign is a unit with no letters in it, so it has no `core` at
    // all: it needs its own check rather than the letters-only `units` lookup
    // below.
    if (line.slice(words.start(word), words.end(word)) == "₪") return NumberEmphasisJoined(words.end(word), false)
    val core = words.core(word) ?: return null
    if (allowingFraction && core in NumberEmphasis.fractionsOf) return NumberEmphasisJoined(words.coreEnd(word), true)
    // After a count "ha-yom" is "today" ("120/80 ha-yom"), as "ha-shavua" is
    // "this week": only a part of it is "the day" ("chatzi ha-yom").
    if (core in NumberEmphasis.thisPeriodWords && !afterFraction) return null
    val withoutArticle = if (core.startsWith("ה") && emphasisCount(core) > 2) emphasisDropFirst(core) else core
    val units = NumberEmphasis.units
    if (!(core in units || withoutArticle in units || core.lowercase(Locale.ROOT) in units)) return null
    return NumberEmphasisJoined(words.coreEnd(word), false)
}

/**
 * Whether [word] continues a compound number or time expression started by the
 * word before it: another number word on its own ("esreh" in "chamesh esreh" -
 * "fifteen"), or one with the "ו" ("and") conjunction ("u-shlosha",
 * "va-chatzi"). Any other prefix ("ba-shlosha" - "at three") means a new,
 * unrelated word, not a continuation. Gives the end of the number word.
 */
private fun emphasisChained(words: NumberEmphasisWords, word: Int): Int? {
    val core = words.core(word) ?: return null
    val reading = emphasisReading(core) ?: return null
    if (!(reading.prefixes.isEmpty() || reading.prefixes == "ו")) return null
    return words.coreEnd(word)
}

private fun emphasisNumberRange(line: NumberEmphasisLine, words: NumberEmphasisWords, position: Int): Pair<Int, Int>? {
    val firstDigit = line.firstAt(words.start(position), words.end(position), ::isEmphasisNumber)
    if (firstDigit >= 0) {
        val lastDigit = line.lastAt(words.start(position), words.end(position), ::isEmphasisNumber)
        var end = lastDigit + 1
        if (end < words.end(position) && line.graphemes[end] in emphasisGluedUnitSigns) end += 1
        return firstDigit to end
    }
    if (!words.mayCount(position)) return null
    val core = words.core(position) ?: return null
    if (isOncePerPeriod(core, position, words)) return words.coreStart(position) to words.coreEnd(position)
    val reading = emphasisReading(core) ?: return null
    val previous = if (position > 0) words.core(position - 1) else null
    val following = ArrayList<String>(3)
    for (next in position + 1 until minOf(words.size, position + 4)) words.core(next)?.let { following.add(it) }
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
        if (words.coreEnd(position) < line.count && isEmphasisWhitespace(line.graphemes[words.coreEnd(position)]) && position + 1 < words.size) {
            val next = words.core(position + 1)
            if (next != null && isDefinitePlural(next) && !(previous?.let { isHour(it) } ?: false)) return null
        }
    }
    if (reading.number in NumberEmphasis.twoWords) {
        // "ha-sheni" ("the other one" / "the second") is never a count of two.
        if ("ה" in reading.prefixes) return null
        // "lishnei" after "echad" is "to each other"; alone it's "to two" or
        // "on Monday".
        val earlier = (maxOf(0, position - 3) until position).mapNotNull { words.core(it) }
        if (core in NumberEmphasis.otherOneWords && earlier.any { it in NumberEmphasis.onesWords }) return null
    }
    return words.coreStart(position) to words.coreEnd(position)
}

/**
 * "pa'am be-yom" ("once a day"), "pa'am be-shlosha yamim" ("once every three
 * days"): how often a pill is taken, which "pa'amayim" ("twice") already stood
 * out for. On its own "pa'am" is mostly another word: "af pa'am" ("never"),
 * "od pa'am ba-boker" ("again in the morning"), "pa'am hayiti" ("I once was").
 */
private fun isOncePerPeriod(core: String, position: Int, words: NumberEmphasisWords): Boolean {
    if (core !in NumberEmphasis.onceWords || position + 1 >= words.size) return false
    val next = words.core(position + 1) ?: return false
    if (!next.startsWith("ב") || emphasisCount(next) <= 2) return false
    if (position > 0) {
        val previous = words.core(position - 1)
        if (previous != null && previous in NumberEmphasis.notACountBefore) return false
    }
    val period = emphasisDropFirst(next)
    return period in NumberEmphasis.periodWords || period in NumberEmphasis.numberWords
}

/**
 * The words of [line], or none when not one of them could be where a
 * number starts: none has a digit, none is a counting word ([mayCount])
 * and, with a [profile], none is one of the words its numbers start at
 * ([NumberWordProfile.mayStartANumber]). Neither pass finds anything on
 * such a line, so it is looked through once, and nothing is made for it.
 * Otherwise its words are counted first, and kept in one array.
 */
private fun emphasisWords(line: NumberEmphasisLine, decimalComma: Boolean, profile: NumberWordProfile?): NumberEmphasisWords {
    var count = 0
    var mayHaveANumber = false
    forEachEmphasisWord(line, decimalComma) { start, end, coreStart, coreEnd ->
        count += 1
        if (!mayHaveANumber) {
            mayHaveANumber = line.firstAt(start, end, ::isEmphasisNumber) >= 0 ||
                (coreStart >= 0 && (line.mayCount(coreStart, coreEnd) ||
                    (profile != null && profile.mayStartANumber(line.text, line.offset(coreStart), line.offset(coreEnd)))))
        }
    }
    if (!mayHaveANumber) return NumberEmphasisWords(line, noWords)
    val bounds = IntArray(4 * count)
    var at = 0
    forEachEmphasisWord(line, decimalComma) { start, end, coreStart, coreEnd ->
        bounds[at] = start
        bounds[at + 1] = end
        bounds[at + 2] = coreStart
        bounds[at + 3] = coreEnd
        at += 4
    }
    return NumberEmphasisWords(line, bounds)
}

private val noWords = IntArray(0)

/** Each word of [line], from character `start` until `end`, its letters from `coreStart` until `coreEnd` (both -1 when it has none). */
private inline fun forEachEmphasisWord(
    line: NumberEmphasisLine,
    decimalComma: Boolean,
    word: (start: Int, end: Int, coreStart: Int, coreEnd: Int) -> Unit,
) {
    var start = -1
    var index = 0
    while (true) {
        val atEnd = index == line.count
        if (atEnd || emphasisSeparates(line, index, decimalComma)) {
            val wordStart = start
            if (wordStart >= 0) {
                val first = line.firstAt(wordStart, index, ::isEmphasisLetter)
                if (first >= 0) {
                    word(wordStart, index, first, line.lastAt(wordStart, index, ::isEmphasisLetter) + 1)
                } else {
                    word(wordStart, index, -1, -1)
                }
                start = -1
            }
        } else if (start < 0) {
            start = index
        }
        if (atEnd) break
        index += 1
    }
}

/**
 * Whitespace always ends a word. A hyphen, maqaf, dash or slash does too
 * ("shlosha-asar" - "thirteen", "be-3" - "in 3"), except between two digits,
 * where it is part of the number ("050-1234567", "3/4", "10:30-11:00").
 */
private fun emphasisSeparates(line: NumberEmphasisLine, index: Int, decimalComma: Boolean): Boolean {
    val character = line.graphemes[index]
    if (isEmphasisWhitespace(character)) return true
    if (character == ",") return !(isThousandsGroupComma(line, index) || (decimalComma && isDecimalComma(line, index)))
    if (character.codePointCount(0, character.length) != 1 || !HebrewText.isWordJoiner(character.codePointAt(0))) return false
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
private fun emphasisFollowedByComma(line: NumberEmphasisLine, index: Int, decimalComma: Boolean): Boolean =
    index < line.count && line.graphemes[index] == "," && !(decimalComma && isDecimalComma(line, index))

private fun isJoinerBetweenDigits(line: NumberEmphasisLine, index: Int): Boolean {
    val character = line.graphemes[index]
    if (character == ",") return true
    return character.codePointCount(0, character.length) == 1 && HebrewText.isWordJoiner(character.codePointAt(0))
}

/**
 * Where a comma is a decimal mark (French, Spanish, German, Portuguese,
 * Russian, Ukrainian: "2,5 mg"): a digit before it, one or two digits after,
 * and neither side part of a longer run of digit groups ("1,2,3", or two phone
 * numbers "050-1234567,03-1234567", stay separate numbers). A hyphen then at
 * most two digits is a range ("0,5-1 mg", "2,5-3,5 mg"), not a phone number.
 */
private fun isDecimalComma(line: NumberEmphasisLine, index: Int): Boolean {
    if (index == 0 || !isEmphasisNumber(line.graphemes[index - 1])) return false
    var start = index - 1
    while (start > 0 && isEmphasisNumber(line.graphemes[start - 1])) start -= 1
    if (start >= 2 && isJoinerBetweenDigits(line, start - 1) && isEmphasisNumber(line.graphemes[start - 2])) {
        val endOfRange = line.graphemes[start - 1] != "," && index - start <= 2
        if (!endOfRange) return false
    }
    var cursor = index + 1
    while (cursor < line.count && isEmphasisNumber(line.graphemes[cursor])) cursor += 1
    val digitCount = cursor - index - 1
    if (digitCount !in 1..2) return false
    if (!(cursor + 1 < line.count && isJoinerBetweenDigits(line, cursor) && isEmphasisNumber(line.graphemes[cursor + 1]))) return true
    if (line.graphemes[cursor] == ",") return false
    var rangeCursor = cursor + 1
    while (rangeCursor < line.count && isEmphasisNumber(line.graphemes[rangeCursor])) rangeCursor += 1
    return rangeCursor - cursor - 1 <= 2
}

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
    val characters = Graphemes.of(word)
    var restStart = 0
    repeat(2) {
        if (restStart >= characters.count || characters[restStart] !in NumberEmphasis.prefixes || characters.count - restStart <= 2) return null
        restStart += 1
        val rest = word.substring(characters.start(restStart))
        if (rest in NumberEmphasis.numberWords) {
            return NumberEmphasisReading(rest, word.substring(0, characters.start(restStart)))
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

private class SpelledTail(val last: Int, val end: Int, val joined: Boolean)

/** What a word starts a sentence after: the end of one, a new line or an opening quote. */
private val sentenceOpenersAfter = listOf(".", "!", "?", "।", "…", "\n", "“", "‘", "«", "\"")

/**
 * The ranges for a language with a word list (see [NumberWordProfile]): counting words
 * in runs ("ten thirty", "two hundred and fifty"), the unit after them ("three pills",
 * "five o'clock", "10:30 pm"), "twice a day" and "half a tablet". "One" and "once" count
 * only with a unit or a period after them, so "the one I like" stays plain.
 */
internal fun spelledRanges(text: String, decimalComma: Boolean, profile: NumberWordProfile): List<OpenEndRange<Int>> {
    val line = NumberEmphasisLine(text)
    return spelledRanges(line, emphasisWords(line, decimalComma, profile), decimalComma, profile)
}

private fun spelledRanges(
    line: NumberEmphasisLine,
    words: NumberEmphasisWords,
    decimalComma: Boolean,
    profile: NumberWordProfile,
): List<OpenEndRange<Int>> {
    fun lowered(index: Int): String? = words.core(index)?.let(profile::normalize)
    fun coreEnd(index: Int): Int = if (words.hasCore(index)) words.coreEnd(index) else words.end(index)
    fun joinsNext(index: Int, end: Int): Boolean =
        index + 1 < words.size && end == words.end(index) && !emphasisFollowedByComma(line, end, decimalComma)
    fun startsSentence(start: Int): Boolean {
        val last = line.lastAt(0, start) { !isEmphasisWhitespace(it) || it == "\n" }
        if (last < 0) return true
        return line.graphemes[last] in sentenceOpenersAfter
    }
    fun isNumber(index: Int): Boolean = lowered(index)?.let { it in profile.numbers } ?: false
    fun hasDigit(index: Int): Boolean = line.firstAt(words.start(index), words.end(index), ::isEmphasisNumber) >= 0
    fun digitEnd(index: Int): Int {
        val last = line.lastAt(words.start(index), words.end(index), ::isEmphasisNumber)
        if (last < 0) throw NoSuchElementException("No digit in word $index.")
        return last + 1
    }

    fun runEnd(start: Int): Int {
        var last = start
        while (joinsNext(last, coreEnd(last))) {
            val next = lowered(last + 1)
            if (isNumber(last + 1)) {
                last += 1
            } else if (next != null && next in profile.connectors && lowered(last) in profile.scales &&
                last + 2 < words.size && isNumber(last + 2) && joinsNext(last + 1, coreEnd(last + 1))
            ) {
                last += 2
            } else {
                break
            }
        }
        return last
    }

    fun unitAfter(last: Int, end: Int, throughLinks: Boolean): Triple<Int, Int, String>? {
        if (!joinsNext(last, end)) return null
        var cursor = last
        if (throughLinks) {
            while (joinsNext(cursor, coreEnd(cursor)) && cursor + 1 < words.size && lowered(cursor + 1) in profile.fractionLinks) cursor += 1
        }
        if (cursor + 1 >= words.size || !joinsNext(cursor, if (cursor == last) end else coreEnd(cursor))) return null
        if (line.slice(words.start(cursor + 1), words.end(cursor + 1)) == "₪") return Triple(cursor + 1, words.end(cursor + 1), "₪")
        val core = lowered(cursor + 1) ?: return null
        return if (core in profile.units) Triple(cursor + 1, coreEnd(cursor + 1), core) else null
    }

    fun repeatedAfter(last: Int, end: Int): Pair<Int, Int>? {
        if (joinsNext(last, end) && lowered(last + 1) in profile.adverbPeriods) return last + 1 to coreEnd(last + 1)
        if (!joinsNext(last, end) || last + 2 >= words.size) return null
        if (lowered(last + 1) !in profile.repeatLinks || !joinsNext(last + 1, coreEnd(last + 1))) return null
        var counted = last + 2
        if (hasDigit(counted)) {
            if (!joinsNext(counted, digitEnd(counted))) return null
        } else if (isNumber(counted)) {
            counted = runEnd(counted)
            if (!joinsNext(counted, coreEnd(counted))) return null
        } else {
            counted -= 1
        }
        if (counted + 1 >= words.size || lowered(counted + 1) !in profile.periods) return null
        return counted + 1 to coreEnd(counted + 1)
    }

    fun mixedFraction(last: Int, end: Int): Pair<Int, Int>? {
        if (!joinsNext(last, end) || lowered(last + 1) !in profile.connectors) return null
        var cursor = last + 1
        while (joinsNext(cursor, coreEnd(cursor)) && cursor + 1 < words.size && lowered(cursor + 1) in profile.fractionLinks) cursor += 1
        if (cursor + 1 >= words.size || !joinsNext(cursor, coreEnd(cursor)) || lowered(cursor + 1) !in profile.fractions) return null
        return cursor + 1 to coreEnd(cursor + 1)
    }

    fun finish(last: Int, end: Int, core: String?, afterFraction: Boolean): SpelledTail {
        var lastWord = last
        var endAt = end
        var tailCore = core
        var joined = false
        unitAfter(last, end, afterFraction)?.let {
            lastWord = it.first
            endAt = it.second
            tailCore = it.third
            joined = true
        }
        if (tailCore != null && tailCore in profile.repeatUnits) {
            repeatedAfter(lastWord, endAt)?.let {
                lastWord = it.first
                endAt = it.second
            }
        }
        return SpelledTail(lastWord, endAt, joined)
    }

    fun minuteEnd(index: Int): Pair<Int, Int>? {
        if (index >= words.size || !isNumber(index) || lowered(index) in profile.ambiguous) return null
        val second = runEnd(index)
        if (finish(second, coreEnd(second), lowered(second), false).joined) return null
        if (joinsNext(second, coreEnd(second)) && lowered(second + 1) in profile.periods) return null
        return second to coreEnd(second)
    }

    fun minuteWithClockUnit(index: Int): Pair<Int, Int>? {
        if (index >= words.size || !isNumber(index) || lowered(index) in profile.ambiguous) return null
        if (runEnd(index) != index || !joinsNext(index, coreEnd(index))) return null
        if (lowered(index + 1) !in profile.clockUnits) return null
        return (index + 1) to coreEnd(index + 1)
    }

    fun numberClock(last: Int, end: Int, cued: Boolean = false): Pair<Int, Int>? {
        if (!joinsNext(last, end) || lowered(last + 1) !in profile.numberClockLinks ||
            !joinsNext(last + 1, coreEnd(last + 1)) || last + 2 >= words.size
        ) {
            return null
        }
        var minute = last + 2
        var cuedByArticle = false
        val article = lowered(minute)
        if (article != null && article in profile.numberClockArticles && joinsNext(minute, coreEnd(minute)) && minute + 1 < words.size) {
            minute += 1
            cuedByArticle = article in profile.clockOneCues
        }
        val one = lowered(minute)
        if ((cued || cuedByArticle) && one != null && one in profile.clockMinuteOnes && !joinsNext(minute, coreEnd(minute))) {
            return minute to coreEnd(minute)
        }
        val found = minuteEnd(minute) ?: minuteWithClockUnit(minute) ?: return null
        if (profile.clockStopsBeforeCapitalized && joinsNext(found.first, found.second)) {
            val noun = lowered(found.first + 1)
            val first = words.core(found.first + 1)?.firstOrNull()
            if (noun != null && noun !in profile.clockUnits && first?.isUpperCase() == true) return null
        }
        return found
    }

    fun hourClock(unit: Int, end: Int): Pair<Int, Int>? {
        if (lowered(unit) !in profile.clockUnits || !joinsNext(unit, end)) return null
        minuteEnd(unit + 1)?.let { return it }
        if (lowered(unit + 1) !in profile.clockUnitLinks || !joinsNext(unit + 1, coreEnd(unit + 1))) return null
        minuteEnd(unit + 2)?.let { return it }
        var cursor = unit + 1
        while (joinsNext(cursor, coreEnd(cursor)) && cursor + 1 < words.size && lowered(cursor + 1) in profile.fractionLinks) {
            cursor += 1
        }
        if (!joinsNext(cursor, coreEnd(cursor)) || cursor + 1 >= words.size || lowered(cursor + 1) !in profile.fractions) return null
        return (cursor + 1) to coreEnd(cursor + 1)
    }

    val result = mutableListOf<OpenEndRange<Int>>()
    var lastJoined = -1
    for (position in 0 until words.size) {
        if (position <= lastJoined) continue
        var start = -1
        var last = -1
        var end = -1
        val firstDigit = line.firstAt(words.start(position), words.end(position), ::isEmphasisNumber)
        if (firstDigit >= 0) {
            var digitsEnd = line.lastAt(words.start(position), words.end(position), ::isEmphasisNumber) + 1
            if (digitsEnd < words.end(position) && line.graphemes[digitsEnd] in emphasisGluedUnitSigns) digitsEnd += 1
            val mixed = mixedFraction(position, digitsEnd)
            val done = if (mixed != null) finish(mixed.first, mixed.second, null, true) else finish(position, digitsEnd, null, false)
            start = firstDigit
            last = done.last
            end = done.end
        } else if (words.mayStartANumber(position, profile)) {
            // A word no number here starts at is passed over before it
            // is made lower case: making every word of the line so made
            // most of what finding the numbers on an English line cost.
            val core = lowered(position) ?: continue
            val previous = if (position > 0) lowered(position - 1) else null
            if (core in profile.onceWords) {
                if (previous != null && previous in profile.notACountBefore) continue
                var found = repeatedAfter(position, coreEnd(position))
                if (found == null && profile.adverbPeriods.isNotEmpty() && joinsNext(position, coreEnd(position)) &&
                    lowered(position + 1) in profile.ambiguous
                ) {
                    found = repeatedAfter(position + 1, coreEnd(position + 1))
                }
                val again = found ?: continue
                start = words.coreStart(position)
                if (previous != null && previous in profile.ambiguous && joinsNext(position - 1, coreEnd(position - 1))) {
                    start = words.coreStart(position - 1)
                }
                last = again.first
                end = again.second
            } else if (isNumber(position)) {
                var runLast = runEnd(position)
                var runStop = coreEnd(runLast)
                var afterFraction = false
                val mixed = mixedFraction(runLast, runStop)
                if (mixed != null) {
                    runLast = mixed.first
                    runStop = mixed.second
                    afterFraction = true
                } else if (joinsNext(runLast, runStop) && lowered(runLast + 1) in profile.fractions) {
                    runLast += 1
                    runStop = coreEnd(runLast)
                    afterFraction = true
                }
                val done = finish(runLast, runStop, lowered(runLast), afterFraction)
                var numberStart = words.coreStart(position)
                val cued = previous != null && previous in profile.clockOneCues
                var hourOne = false
                var clock = when {
                    afterFraction -> null
                    done.joined -> hourClock(done.last, done.end)
                    core !in profile.ambiguous -> numberClock(runLast, runStop, cued)
                    else -> null
                }
                if (clock == null && !afterFraction && !done.joined && position == runLast && cued && core in profile.clockHourOnes) {
                    clock = numberClock(runLast, runStop)
                    hourOne = clock != null
                }
                if (clock == null && position == runLast && core in profile.plainAlone && !done.joined &&
                    !(previous != null && previous in profile.countsAfter)
                ) {
                    continue
                }
                if (position == runLast && !hourOne && core in profile.ambiguous) {
                    if (previous != null && previous in profile.notACountBefore) continue
                    if (done.joined && done.last == position + 1 && "$core ${lowered(done.last)}" in profile.storyOpeners &&
                        startsSentence(words.coreStart(position)) && !(position + 2 < words.size && lowered(position + 2) in profile.storyKeeps)
                    ) {
                        continue
                    }
                    if (!done.joined) {
                        val unitBefore = profile.oneFollowsUnit && previous != null && previous in profile.units &&
                            previous !in profile.onceWords && joinsNext(position - 1, coreEnd(position - 1))
                        if (!unitBefore) continue
                        numberStart = words.coreStart(position - 1)
                    }
                }
                start = numberStart
                val keepTail = done.joined || !afterFraction
                last = clock?.first ?: if (keepTail) done.last else runLast
                end = clock?.second ?: if (keepTail) done.end else runStop
            } else if (core in profile.clockOrdinals && position > 0 && lowered(position - 1) in profile.clockNouns &&
                joinsNext(position - 1, coreEnd(position - 1))
            ) {
                var clockLast = position
                val next = if (joinsNext(clockLast, coreEnd(clockLast))) lowered(clockLast + 1) else null
                if (next != null && next in profile.numbers) {
                    clockLast = runEnd(clockLast + 1)
                } else if (next != null && next in profile.fractions) {
                    clockLast += 1
                }
                start = words.coreStart(position - 1)
                last = clockLast
                end = coreEnd(clockLast)
            } else if (core in profile.fractions) {
                val clock = joinsNext(position, coreEnd(position)) && lowered(position + 1) in profile.clockLinks &&
                    joinsNext(position + 1, coreEnd(position + 1)) && position + 2 < words.size &&
                    (isNumber(position + 2) || hasDigit(position + 2))
                if (clock) {
                    val runLast = if (hasDigit(position + 2)) position + 2 else runEnd(position + 2)
                    val done = finish(runLast, if (hasDigit(runLast)) digitEnd(runLast) else coreEnd(runLast), null, false)
                    start = words.coreStart(position)
                    last = done.last
                    end = done.end
                } else {
                    val done = finish(position, coreEnd(position), null, true)
                    if (!done.joined) continue
                    start = words.coreStart(position)
                    last = done.last
                    end = done.end
                }
            }
        }
        if (start < 0) continue
        result.add(IntRange(line.offset(start), line.offset(end) - 1))
        lastJoined = last
    }
    return result
}

internal fun spelledListable(text: String, decimalComma: Boolean, profile: NumberWordProfile): Boolean =
    spelledRanges(text, decimalComma, profile).any { range ->
        val found = emphasisCharacters(text.substring(range.start, range.endExclusive))
        found.any { isEmphasisNumber(it) } ||
            found.any { isEmphasisWhitespace(it) || it == "-" } ||
            found.joinToString("").lowercase(Locale.ROOT) !in profile.idioms
    }
