package com.arbelonson.ozen.core

data class TranscribedLine(val startSeconds: Double, val endSeconds: Double, val text: String)

object TranscriptFormats {
    private const val LEAST_CUE_SECONDS = 0.5

    fun clock(seconds: Double): String {
        val whole = maxOf(0L, Math.floor(seconds).toLong())
        val hours = whole / 3_600
        val minutes = whole % 3_600 / 60
        val rest = whole % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
    }

    fun text(lines: List<TranscribedLine>): String = lines.joinToString("\n") { "${clock(it.startSeconds)}  ${it.text}" }

    fun srt(lines: List<TranscribedLine>): String = lines.withIndex().joinToString("\n") { (index, line) ->
        "${index + 1}\n${stamp(line.startSeconds, ',')} --> ${stamp(end(line), ',')}\n${oneParagraph(line.text)}\n"
    }

    fun vtt(lines: List<TranscribedLine>): String =
        "WEBVTT\n" + lines.joinToString("") { line -> "\n${stamp(line.startSeconds, '.')} --> ${stamp(end(line), '.')}\n${oneParagraph(line.text)}\n" }

    private fun end(line: TranscribedLine): Double = maxOf(line.endSeconds, line.startSeconds + LEAST_CUE_SECONDS)

    private fun oneParagraph(text: String): String = text.trim().split(Regex("\\s*\\n\\s*")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun stamp(seconds: Double, separator: Char): String {
        val millis = maxOf(0L, Math.floor(seconds * 1_000).toLong())
        return "%02d:%02d:%02d%c%03d".format(millis / 3_600_000, millis / 60_000 % 60, millis / 1_000 % 60, separator, millis % 1_000)
    }
}
