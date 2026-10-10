package com.arbelonson.ozen.core

import java.util.regex.Pattern

object PhoneNumbers {
    data class Match(val start: Int, val end: Int, val dialable: String) {
        val url: String get() = "tel:$dialable"

        fun textIn(text: String): String = text.substring(start, end)
    }

    fun matches(text: String): List<Match> {
        val services = servicePattern.matcher(text).results().map { found ->
            Match(found.start(), found.end(), digitsOf(found.group()))
        }.toList()
        val phones = pattern.matcher(text).results().map { found ->
            val written = found.group()
            val digits = digitsOf(written)
            when {
                written.startsWith("+") ->
                    if (digits.startsWith("972") && digits.length in 11..12) Match(found.start(), found.end(), "+$digits") else null
                digits.length in 9..10 -> Match(found.start(), found.end(), digits)
                else -> null
            }
        }.toList().filterNotNull()
        return (services + phones).sortedBy { it.start }
    }

    private fun digitsOf(written: String): String = written.filter { it in '0'..'9' }

    private val pattern: Pattern = Pattern.compile(
        """(?<![\d+])(?!\d{1,2}[-./]\d{1,2}[-./]\d{2,4})(?:\+972[- ]?|0)(?:[2-489](?:[- ]?\d){7}|[57](?:[- ]?\d){8})(?!-?\d)""",
        Pattern.UNICODE_CHARACTER_CLASS,
    )

    private val servicePattern: Pattern = Pattern.compile(
        """(?<![\d+*])1[- ]?(?:700|800|801|599)(?:[- ]?\d){6}(?!\d)""",
        Pattern.UNICODE_CHARACTER_CLASS,
    )
}
