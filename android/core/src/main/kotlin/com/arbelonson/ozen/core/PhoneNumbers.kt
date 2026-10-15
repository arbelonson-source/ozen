package com.arbelonson.ozen.core

import java.util.regex.Pattern

object PhoneNumbers {
    data class Match(val start: Int, val end: Int, val dialable: String) {
        val url: String get() = "tel:$dialable"

        fun textIn(text: String): String = text.substring(start, end)
    }

    fun matches(text: String): List<Match> {
        val found = ArrayList<Match>()
        val services = servicePattern.matcher(text)
        while (services.find()) {
            found.add(Match(services.start(), services.end(), digitsOf(services.group())))
        }
        val phones = pattern.matcher(text)
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

    private fun digitsOf(written: String): String = written.codePoints().map { Character.digit(it, 10) }.filter { it >= 0 }.toArray().joinToString("")

    private val pattern: Pattern = unicodePattern(
        """(?<![\d+])(?!\d{1,2}[-./]\d{1,2}[-./]\d{2,4})(?:\+972[- \u00A0\u202F]?|0)(?:[2-489](?:[- \u00A0\u202F]?\d){7}|[57](?:[- \u00A0\u202F]?\d){8})(?!-?\d)""",
    )

    private val servicePattern: Pattern = unicodePattern(
        """(?<![\d+*])1[- \u00A0\u202F]?(?:700|800|801|599)(?:[- \u00A0\u202F]?\d){6}(?!-?\d)""",
    )
}
