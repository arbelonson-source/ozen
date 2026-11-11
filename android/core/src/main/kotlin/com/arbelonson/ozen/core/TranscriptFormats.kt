package com.arbelonson.ozen.core

import java.util.Locale

data class TranscribedLine(val startSeconds: Double, val endSeconds: Double, val text: String)

object TranscriptFormats {
    private const val LEAST_CUE_SECONDS = 0.5
    private const val RIGHT_TO_LEFT_MARK = "\u200F"
    private val lineBreaks = Regex("[\n\u000B\u000C\r\u0085\u2028\u2029]")

    fun clock(seconds: Double): String {
        val whole = wholeSeconds(seconds)
        val hours = whole / 3_600
        val minutes = whole % 3_600 / 60
        val rest = whole % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, rest) else "%d:%02d".format(Locale.ROOT, minutes, rest)
    }

    fun text(lines: List<TranscribedLine>, languageCode: String): String = lines.joinToString("\n") { line ->
        directed("${clock(line.startSeconds)}  ${paragraph(line.text)}", line.text, languageCode)
    }

    fun srt(lines: List<TranscribedLine>, languageCode: String): String = lines.withIndex().joinToString("\n") { (index, line) ->
        "${index + 1}\n${stamp(line.startSeconds, ',')} --> ${stamp(end(line), ',')}\n${cue(line, languageCode)}\n"
    }

    fun vtt(lines: List<TranscribedLine>, languageCode: String): String =
        "WEBVTT\n" + lines.joinToString("") { line -> "\n${stamp(line.startSeconds, '.')} --> ${stamp(end(line), '.')}\n${cue(line, languageCode)}\n" }

    private fun end(line: TranscribedLine): Double = maxOf(line.endSeconds, line.startSeconds + LEAST_CUE_SECONDS)

    private fun cue(line: TranscribedLine, languageCode: String): String = directed(paragraph(line.text), line.text, languageCode)

    private fun paragraph(text: String): String =
        CaptionLayout.copiedText(text.split(lineBreaks).map { it.trim(::isSpace) }.filter { it.isNotEmpty() }.joinToString(" "))

    private fun isSpace(character: Char): Boolean = character == '\t' || Character.getType(character) == Character.SPACE_SEPARATOR.toInt()

    private fun directed(line: String, text: String, languageCode: String): String =
        if (CaptionLayout.readsRightToLeft(text, languageCode)) RIGHT_TO_LEFT_MARK + line else line

    private fun wholeSeconds(seconds: Double): Long = if (seconds.isFinite()) Math.floor(seconds.coerceIn(0.0, 1e9)).toLong() else 0L

    private fun stamp(seconds: Double, separator: Char): String {
        val millis = if (seconds.isFinite()) Math.floor(seconds.coerceIn(0.0, 1e7) * 1_000 + 1e-6).toLong() else 0L
        return "%02d:%02d:%02d%c%03d".format(Locale.ROOT, millis / 3_600_000, millis / 60_000 % 60, millis / 1_000 % 60, separator, millis % 1_000)
    }
}
