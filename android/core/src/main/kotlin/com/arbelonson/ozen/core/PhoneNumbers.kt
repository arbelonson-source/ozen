package com.arbelonson.ozen.core

import java.util.regex.Pattern

object PhoneNumbers {
    data class Match(val start: Int, val end: Int, val dialable: String) {
        val url: String get() = "tel:$dialable"

        fun textIn(text: String): String = text.substring(start, end)
    }

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
        return found.sortedBy { it.start }
    }

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
}
