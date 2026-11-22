package com.arbelonson.ozen.core

import java.text.BreakIterator
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The caption layout as it was before the line still being said stopped
 * making a string for each of its characters at every word, kept here as it
 * was so the faster code is held to drawing exactly what it drew: the same
 * paragraphs, direction marks, joined numbers and lock screen tails. A
 * deliberate change to these rules changes this copy the same way.
 */
private object Before {
    private const val PARAGRAPH_CHARACTERS = 90
    private const val NO_BREAK_SPACE = ' '
    private const val NON_BREAKING_HYPHEN = '‑'
    private val doseSpace = unicodePattern("""(?<=\d) (?=(?:מיליגרם|מיליליטר|מ״ג|מ״ל|גרם|ליטר|mg|ml)(?![\p{L}]))""")
    private val neutralEnders = setOf(".", "!", "?", ")", ":")
    private val enders = setOf(".", "?", "!", "…")
    private val closers = setOf("\"", "'", "”", "’", ")", "]", "״", "׳")

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

    fun displayText(text: String, languageCode: String): String = directed(joiningNumbers(readableText(text), languageCode), languageCode)

    fun joiningNumbers(text: String, languageCode: String): String {
        val phones = PhoneNumbers.matches(text).map { it.start until it.end }
        val amounts = NumberEmphasis.ranges(text, languageCode = languageCode).map { it.start until it.endExclusive }
            .filter { span -> span.count { text[it] == ' ' } == 1 }
        val result = StringBuilder(text)
        for (span in phones + amounts) {
            for (index in span) if (result[index] == ' ') result.setCharAt(index, NO_BREAK_SPACE)
        }
        for (span in phones) {
            for (index in span) if (result[index] == '-') result.setCharAt(index, NON_BREAKING_HYPHEN)
        }
        return doseSpace.matcher(result).replaceAll(NO_BREAK_SPACE.toString())
    }

    fun directed(text: String, languageCode: String): String {
        if (!CaptionLayout.isRightToLeft(languageCode)) return text
        return CaptionLayout.isolatingNumbers(text)
            .split("\n")
            .joinToString("\n") { paragraph ->
                if (CaptionLayout.hasOnlyLeftToRightLetters(paragraph)) paragraph else CaptionLayout.RIGHT_TO_LEFT_MARK + anchorTrailingPunctuation(paragraph)
            }
    }

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
        return line + CaptionLayout.RIGHT_TO_LEFT_MARK
    }

    fun splitSentences(text: String): List<String> {
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

    fun tail(text: String, maximumCharacters: Int): String {
        val trimmed = text.trim()
        val starts = letterStarts(trimmed)
        if (starts.size <= maximumCharacters || maximumCharacters <= 1) return trimmed
        val cutPoint = starts[starts.size - (maximumCharacters - 1)]
        var kept = trimmed.substring(cutPoint)
        val cutMidWord = cutPoint > 0 && !trimmed[cutPoint - 1].isWhitespace()
        val space = kept.indexOfFirst { it.isWhitespace() }
        if (cutMidWord && space >= 0) kept = kept.substring(space + 1)
        return "…$kept"
    }

    fun asciiDigits(text: String): String = buildString(text.length) {
        for (c in text) append(if (Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER.toInt()) '0' + Character.digit(c, 10) else c)
    }

    fun digitsOf(written: String): String = written.codePoints().map { Character.digit(it, 10) }.filter { it >= 0 }.toArray().joinToString("")

    fun stripNiqqud(text: String): String {
        val result = StringBuilder(text.length)
        text.codePoints().forEach { if (!(it in 0x0591..0x05C7 && Character.getType(it) == Character.NON_SPACING_MARK.toInt())) result.appendCodePoint(it) }
        return result.toString()
    }

    private fun letterStarts(text: String): List<Int> {
        val letters = BreakIterator.getCharacterInstance(Locale.ROOT)
        letters.setText(text)
        val starts = ArrayList<Int>(text.length)
        var boundary = letters.first()
        while (boundary != BreakIterator.DONE && boundary < text.length) {
            starts += boundary
            boundary = letters.next()
        }
        return starts
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

    private fun trimmingSpaces(text: String): String {
        val scalars = text.codePoints().toArray()
        var from = 0
        var to = scalars.size
        val isTrimmed = { it: Int -> Character.getType(it).toByte() == Character.SPACE_SEPARATOR || it == 0x09 }
        while (from < to && isTrimmed(scalars[from])) from++
        while (to > from && isTrimmed(scalars[to - 1])) to--
        return String(scalars, from, to - from)
    }
}

class CaptionLayoutSameAsBeforeTest {
    /** Words, numbers and marks caption lines are made of, the awkward ones included: points, accents, emoji, CR LF, direction marks. */
    private val pieces = listOf(
        "אז", "אמרתי", "לו", "שנפגש", "מחר", "בשעה", "10:30", "ליד", "הבית.", "הוא", "אמר", "שזה", "בסדר,", "ושאני", "אתקשר",
        "ל־052-1234567", "050 123 4567", "*2700", "+972-3-1234567", "1-800-123-456", "(212) 555-1234", "call me at 212 555 1234",
        "3", "כדורים", "פעמיים", "ביום", "חצי", "כוס", "500", "מ״ג", "5 mg", "10 ml", "2,5 mg", "1,234", "3.5", "ד\"ר", "שלושה", "ושלוש",
        "עשרים", "ואחת", "פעם", "בשבוע", "OK,", "WhatsApp.", "Acamol!", "נתראה!", "מה?!", "…", "\"כן.\"", "(כן)", "בסדר]", "׳", "״",
        "שָׁלוֹם", "בְּ", "café", "café", "😀", "👍🏽", "🇮🇱", "‏", "‎", "⁦", "⁩", " ", "\t", "\r\n", "\n",
        "مرحبا", "٣", "٠٥٠١٢٣٤٥٦٧", "سَلام", "؀", "Привет,", "три", "таблетки", "你好。", "三点", "twice", "a", "day", "three",
        "pills", "ten", "thirty", "half", "past", "four", "and", "a", "half", "one", "o'clock", "₪", "50%", "20 ₪", "?", ".", "!", ":", ")",
    )
    private val gaps = listOf(" ", " ", " ", " ", "  ", "", "\n", "\t", " ", ". ", "? ", "! ", ", ", " \n ")

    private fun texts(): List<String> {
        val random = Random(724)
        val made = ArrayList<String>()
        repeat(1_500) {
            made += buildString {
                repeat(random.nextInt(1, 70)) {
                    if (it > 0) append(gaps.random(random))
                    append(pieces.random(random))
                }
            }
        }
        // A line as it grows a word at a time, past where it breaks into paragraphs.
        val said = (1..60).joinToString(" ") { pieces[(it * 7) % pieces.size] }.split(" ")
        for (count in 1..said.size) made += said.take(count).joinToString(" ")
        made += listOf("", " ", "\n", "...", ". . .", "שלום.  ", "  שלום. מה שלומך?  ", "\r\n\r\n")
        return made
    }

    @Test
    fun `the faster layout draws every line exactly as before, in every direction`() {
        val failures = ArrayList<String>()
        fun check(label: String, text: String, before: Any, now: Any) {
            if (before != now && failures.size < 20) failures += "$label \"$text\": before $before, now $now"
        }
        val languages = listOf("he", "en", "ar", "ru", "zh", "fr", "iw", "he-IL")
        for (text in texts()) {
            check("splitSentences", text, Before.splitSentences(text), CaptionLayout.splitSentences(text))
            check("readableText", text, Before.readableText(text), CaptionLayout.readableText(text))
            check("readableText 20", text, Before.readableText(text, 20), CaptionLayout.readableText(text, 20))
            check("asciiDigits", text, Before.asciiDigits(text), PhoneNumbers.asciiDigits(text))
            check("stripNiqqud", text, Before.stripNiqqud(text), HebrewText.stripNiqqud(text))
            val phones = PhoneNumbers.matches(text)
            check("phone digits", text, phones.map { Before.digitsOf(it.textIn(text)) }, phones.map { it.dialable.removePrefix("+") })
            for (room in listOf(1, 2, 5, 20, 60)) check("tail $room", text, Before.tail(text, room), LockScreenCaptions.tail(text, room))
            for (language in languages) {
                check("displayText $language", text, Before.displayText(text, language), CaptionLayout.displayText(text, language))
                check("directed $language", text, Before.directed(text, language), CaptionLayout.directed(text, language))
                check("joiningNumbers $language", text, Before.joiningNumbers(text, language), CaptionLayout.joiningNumbers(text, language))
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the lines checked break into paragraphs, carry marks and joined numbers, and some go to the BreakIterator`() {
        val made = texts()
        assertTrue(made.count { CaptionLayout.readableText(it) != it } > 200)
        assertTrue(made.count { CaptionLayout.joiningNumbers(it, "he") != it } > 200)
        assertTrue(made.count { CaptionLayout.displayText(it, "he").endsWith(CaptionLayout.RIGHT_TO_LEFT_MARK) } > 10)
        assertTrue(made.count { !Graphemes.splitsAtEveryUnit(it) } > 200)
        assertTrue(made.count { Graphemes.splitsAtEveryUnit(it) && it.length > 90 } > 50)
        assertEquals(Before.displayText("OK, נתראה מחר", "he"), CaptionLayout.displayText("OK, נתראה מחר", "he"))
    }
}
