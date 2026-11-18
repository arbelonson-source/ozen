package com.arbelonson.ozen.core

import java.util.Locale
import java.util.UUID

object ProblemSnapshot {
    const val lineCount = 4
    private const val CAPTION_LINE_PREFIX = "  line ("
    const val HEADER_PREFIX = "PROBLEM MARKED:"
    private const val CONVERSATION_MARKER = " conversation "

    fun isCaptionLine(text: String): Boolean = text.startsWith(CAPTION_LINE_PREFIX)

    fun isHeader(text: String): Boolean = text.startsWith(HEADER_PREFIX)

    fun conversationIdOfLine(line: String): UUID? {
        if (!isCaptionLine(line)) return null
        val head = line.substringBefore("): ")
        val at = head.lastIndexOf(CONVERSATION_MARKER)
        return if (at < 0) null else parseUuid(head.substring(at + CONVERSATION_MARKER.length))
    }

    fun conversationId(header: String): UUID? {
        if (!isHeader(header)) return null
        val at = header.lastIndexOf(CONVERSATION_MARKER)
        if (at < 0) return null
        return parseUuid(header.substring(at + CONVERSATION_MARKER.length))
    }

    private fun parseUuid(text: String): UUID? = try {
        UUID.fromString(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun lines(
        settings: AppSettings,
        activeEngine: TranscriptionEngineKind?,
        input: AudioInputDescriptor?,
        stats: PipelineStats,
        segments: List<TranscriptSegment>,
        device: String,
        utcOffsetSeconds: Int,
        includeCaptions: Boolean = true,
        held: TranscriptSegment? = null,
        conversationId: UUID? = null,
        conversationOf: (TranscriptSegment) -> UUID? = { null },
    ): List<String> {
        val engine = activeEngine ?: settings.engine
        val microphone = input?.let { "${it.portName} [${it.portType.rawValue}]" } ?: "-"
        val lines = mutableListOf(
            "$HEADER_PREFIX engine ${engine.rawValue} model ${settings.modelDescription(engine) ?: "-"} lang ${settings.languageCode} microphone $microphone${conversationId?.let { "$CONVERSATION_MARKER$it" } ?: ""}",
            "  lag ${number(stats.captionLagSeconds, "%.2f")}s levels ${stats.inputLevels.summary ?: "-"} speech ${number(stats.speechShare?.let { it * 100 }, "%.0f")}% floor ${number(stats.noiseFloorDecibels, "%.1f")} margin ${number(stats.noiseMarginDecibels, "%.1f")}dB restarts ${stats.engineRestarts} stalls ${stats.audioStalls} updates ${stats.tokensReceived} lines ${stats.segmentsCommitted}",
            "  $device",
        )
        if (!includeCaptions) lines.add("  caption lines not kept (saving conversations is off)")
        if (!includeCaptions) return lines
        // The line held to mark the problem is the one she found wrong: it
        // goes in, marked, even when it is older than the newest few.
        val newest = segments.takeLast(lineCount)
        for (segment in newest) lines.add(line(segment, segment.id == held?.id, utcOffsetSeconds, other(conversationOf(segment), conversationId)))
        if (held != null && newest.none { it.id == held.id }) lines.add(line(held, true, utcOffsetSeconds, other(conversationOf(held), conversationId)))
        return lines
    }

    private fun other(owner: UUID?, header: UUID?): UUID? = if (owner == header) null else owner

    private fun line(segment: TranscriptSegment, held: Boolean, utcOffsetSeconds: Int, owner: UUID?): String {
        val sureness = segment.confidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
        val time = TranscriptHistoryStore.formattedClockTime(segment.lastUpdateTimestamp, utcOffsetSeconds)
        return "$CAPTION_LINE_PREFIX${if (held) "held, " else ""}${if (segment.isCommitted) "final" else "live"}, sure $sureness, $time${owner?.let { "$CONVERSATION_MARKER$it" } ?: ""}): ${segment.text}"
    }

    private fun number(value: Double?, format: String): String =
        value?.let { String.format(Locale.ROOT, format, it) } ?: "-"
}
