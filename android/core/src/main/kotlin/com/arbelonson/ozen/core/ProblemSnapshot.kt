package com.arbelonson.ozen.core

import java.util.Locale

object ProblemSnapshot {
    const val lineCount = 4
    private const val CAPTION_LINE_PREFIX = "  line ("

    fun isCaptionLine(text: String): Boolean = text.startsWith(CAPTION_LINE_PREFIX)

    fun lines(
        settings: AppSettings,
        activeEngine: TranscriptionEngineKind?,
        input: AudioInputDescriptor?,
        stats: PipelineStats,
        segments: List<TranscriptSegment>,
        device: String,
        utcOffsetSeconds: Int,
    ): List<String> {
        val engine = activeEngine ?: settings.engine
        val microphone = input?.let { "${it.portName} [${it.portType.rawValue}]" } ?: "-"
        val lines = mutableListOf(
            "PROBLEM MARKED: engine ${engine.rawValue} model ${settings.modelDescription(engine) ?: "-"} lang ${settings.languageCode} microphone $microphone",
            "  lag ${number(stats.captionLagSeconds, "%.2f")}s levels ${stats.inputLevels.summary ?: "-"} speech ${number(stats.speechShare?.let { it * 100 }, "%.0f")}% floor ${number(stats.noiseFloorDecibels, "%.1f")} margin ${number(stats.noiseMarginDecibels, "%.1f")}dB restarts ${stats.engineRestarts} stalls ${stats.audioStalls} updates ${stats.tokensReceived} lines ${stats.segmentsCommitted}",
            "  $device",
        )
        for (segment in segments.takeLast(lineCount)) {
            val sureness = segment.confidence?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
            val time = TranscriptHistoryStore.formattedClockTime(segment.lastUpdateTimestamp, utcOffsetSeconds)
            lines.add("$CAPTION_LINE_PREFIX${if (segment.isCommitted) "final" else "live"}, sure $sureness, $time): ${segment.text}")
        }
        return lines
    }

    private fun number(value: Double?, format: String): String =
        value?.let { String.format(Locale.ROOT, format, it) } ?: "-"
}
