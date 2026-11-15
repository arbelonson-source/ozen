package com.arbelonson.ozen.core

import java.util.regex.Pattern

object PhoneNumbers {
    data class Match(val start: Int, val end: Int, val dialable: String) {
        val url: String get() = "tel:$dialable"

        fun textIn(text: String): String = text.substring(start, end)
    }

    /**
     * The number as a screen reader should say it, for the Call action: each
     * digit on its own, so it can be checked against what was said (a bare
     * "+972501234567" can be read as one number in the billions), with a
     * comma between groups for a pause. The groups are the ones written in
     * the caption; a run of more than four digits is cut into threes, the
     * last taking a spare digit. A "+" stays on the first digit.
     */
    fun spokenForm(match: Match, text: String): String {
        val written = match.textIn(text)
        val runs = ArrayList<String>()
        val run = StringBuilder()
        for (digit in written.codePoints().toArray().map { Character.digit(it, 10) } + -1) {
            if (digit >= 0) run.append(digit) else if (run.isNotEmpty()) { runs.add(run.toString()); run.setLength(0) }
        }
        val spoken = runs.flatMap { spokenGroups(it) }
            .joinToString(", ") { group -> group.toList().joinToString(" ") }
        return if (written.startsWith("+")) "+$spoken" else spoken
    }

    private fun spokenGroups(run: String): List<String> =
        if (run.length <= 4) listOf(run) else listOf(run.take(3)) + spokenGroups(run.drop(3))

    fun matches(text: String): List<Match> {
        val found = ArrayList<Match>()
        val read = asciiDigits(text)
        val services = servicePattern.matcher(read)
        while (services.find()) {
            found.add(Match(services.start(), services.end(), digitsOf(services.group())))
        }
        val phones = pattern.matcher(read)
        while (phones.find()) {
            val written = phones.group()
            val digits = digitsOf(written)
            if (written.startsWith("+")) {
                if (digits.startsWith("972") && digits.length in 11..12) found.add(Match(phones.start(), phones.end(), "+$digits"))
            } else if (digits.length in 9..10) {
                found.add(Match(phones.start(), phones.end(), digits))
            }
        }
        for (item in abroad(read)) {
            if (found.none { it.start < item.end && item.start < it.end }) found.add(item)
        }
        return found.sortedBy { it.start }
    }

    /**
     * Numbers written for other countries: "+" and a country code (8 to 15
     * digits in all; +972 stays with the Israeli rules above), and North
     * American numbers in the 3-3-4 grouping with separators. Short local
     * numbers, dates, times, amounts and card numbers are not matched.
     */
    private fun abroad(read: String): List<Match> {
        val found = ArrayList<Match>()
        val international = internationalPattern.matcher(read)
        while (international.find()) {
            val digits = digitsOf(international.group())
            if (digits.length in 8..15 && !digits.startsWith("972")) found.add(Match(international.start(), international.end(), "+$digits"))
        }
        val northAmerican = northAmericanPattern.matcher(read)
        while (northAmerican.find()) {
            if (looksWritten(northAmerican.group()) || callWordBefore(read.substring(0, northAmerican.start()))) {
                found.add(Match(northAmerican.start(), northAmerican.end(), digitsOf(northAmerican.group())))
            }
        }
        return found
    }

    /**
     * True when a North American run is written the way a phone number is:
     * parentheses, a dash or dot between the groups, or a leading 1. Digit
     * groups with only spaces between them are also how amounts and years
     * are said, so those need a call word before them ([callWordBefore]).
     */
    private fun looksWritten(run: String): Boolean = run.startsWith("1") || run.any { it == '(' || it == '.' || it == '-' || it == '\u2011' }

    private fun callWordBefore(before: String): Boolean = callWordPattern.matcher(before.takeLast(40)).find()

    private val callWordPattern: Pattern = Pattern.compile("""\b(?:call|calls|calling|dial|dialing|phone|tel|number|reach)\b(?:\s+(?:me|us|him|her|them|at|on|is|are|my|our|the|a|line|number)\b)*\s*[:,]?\s*$""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS)

    /**
     * [text] with every digit of another script (Arabic-Indic, Persian,
     * Devanagari, full-width) written as 0-9, as on the iPhone, so a number
     * said in Arabic is found whatever its first digits look like. Each such
     * digit is one UTF-16 unit either way, so a match's place is the same in [text].
     */
    internal fun asciiDigits(text: String): String = buildString(text.length) {
        for (c in text) append(if (Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER.toInt()) '0' + Character.digit(c, 10) else c)
    }

    private fun digitsOf(written: String): String = written.codePoints().map { Character.digit(it, 10) }.filter { it >= 0 }.toArray().joinToString("")

    private val pattern: Pattern = unicodePattern(
        """(?<![\d+])(?!\d{1,2}[-./]\d{1,2}[-./]\d{2,4})(?:\+972[- \u00A0\u202F\u2011]?|0)(?:[2-489](?:[- \u00A0\u202F\u2011]?\d){7}|[57](?:[- \u00A0\u202F\u2011]?\d){8})(?![-\u2011\u00A0\u202F]?\d)""",
    )

    private val servicePattern: Pattern = unicodePattern(
        """(?<![\d+*])1[- \u00A0\u202F\u2011]?(?:700|800|801|599)(?:[- \u00A0\u202F\u2011]?\d){6}(?![-\u2011\u00A0\u202F]?\d)""",
    )

    private const val SEPARATOR = """[-\u2011\u00A0\u202F .]"""

    private val internationalPattern: Pattern = unicodePattern(
        """(?<![\d+])\+[1-9](?:$SEPARATOR?\d)*(?:$SEPARATOR?\(\d{2,5}\)(?:$SEPARATOR?\d)*)?(?!$SEPARATOR?\d)""",
    )

    private val northAmericanPattern: Pattern = unicodePattern(
        """(?<![\d+*])(?<!\d$SEPARATOR)(?:1$SEPARATOR)?(?:\([2-9]\d\d\)$SEPARATOR?|[2-9]\d\d$SEPARATOR)[2-9]\d\d$SEPARATOR\d{4}(?!$SEPARATOR?\d)""",
    )
}
