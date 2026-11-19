package com.arbelonson.ozen.core

import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

object ClockFormat {
    @Volatile
    var phoneUses24Hours: () -> Boolean? = { null }

    fun formatter(dateStyle: FormatStyle?, timeStyle: FormatStyle, locale: Locale, use24Hours: Boolean? = phoneUses24Hours()): DateTimeFormatter {
        val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(dateStyle, timeStyle, IsoChronology.INSTANCE, locale)
        return DateTimeFormatter.ofPattern(hours(pattern, use24Hours, locale), locale)
    }

    fun hours(pattern: String, use24Hours: Boolean?, locale: Locale): String {
        if (use24Hours == null) return pattern
        val parts = split(pattern)
        val has12 = parts.any { !it.quoted && it.text.any { c -> c == 'h' || c == 'K' || c == 'a' || c == 'B' } }
        val has24 = parts.any { !it.quoted && it.text.any { c -> c == 'H' || c == 'k' } }
        return when {
            use24Hours && has12 -> to24(parts)
            !use24Hours && has24 && !has12 -> to12(parts, locale)
            else -> pattern
        }
    }

    private class Part(val text: String, val quoted: Boolean)

    private fun split(pattern: String): List<Part> {
        val parts = mutableListOf<Part>()
        var i = 0
        while (i < pattern.length) {
            val quoted = pattern[i] == '\''
            val start = i
            i++
            while (i < pattern.length) {
                if (quoted) {
                    if (pattern[i] == '\'') {
                        if (i + 1 < pattern.length && pattern[i + 1] == '\'') i++ else { i++; break }
                    }
                } else if (pattern[i] == '\'') break
                i++
            }
            parts += Part(pattern.substring(start, i), quoted)
        }
        return parts
    }

    private val spaces = "   ‏‎"

    private fun to24(parts: List<Part>): String {
        val out = StringBuilder()
        for (part in parts) {
            if (part.quoted) { out.append(part.text); continue }
            out.append(part.text.replace(Regex("[hK]+"), "HH").replace(Regex("k+"), "HH"))
        }
        var result = out.toString()
        result = result.replace(Regex("[$spaces]*[aB]+[$spaces]*(?=(?:[^']|'[^']*')*$)"), " ")
        if (parts.firstOrNull()?.text?.startsWith("a") == true || result.startsWith(" ")) result = result.trimStart(*spaces.toCharArray())
        return result.trimEnd(*spaces.toCharArray())
    }

    private fun to12(parts: List<Part>, locale: Locale): String {
        val body = StringBuilder()
        val timeField = mutableListOf<Boolean>()
        for (part in parts) {
            val text = if (part.quoted) part.text else part.text.replace(Regex("[Hk]+"), "h")
            body.append(text)
            text.forEach { timeField += !part.quoted && (it == 'h' || it == 'm' || it == 's') }
        }
        if (locale.language in marksFirst) {
            val at = timeField.indexOf(true)
            return body.insert(at, "a ").toString()
        }
        return body.insert(timeField.lastIndexOf(true) + 1, " a").toString()
    }

    private val marksFirst = setOf("zh", "ja", "ko")
}
