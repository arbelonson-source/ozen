package com.arbelonson.ozen.core

import java.util.TimeZone
import java.util.UUID

object FileTranscriptRecord {
    fun make(
        fileName: String?,
        lines: List<TranscribedLine>,
        finishedAt: Double,
        recordingSeconds: Double?,
        modelVariant: String?,
        id: UUID = UUID.randomUUID(),
        languageCode: String? = null,
        cutShort: Boolean = false,
    ): TranscriptSessionRecord {
        val startedAt = finishedAt - maxOf(recordingSeconds ?: 0.0, lines.maxOfOrNull { it.endSeconds } ?: 0.0)
        return TranscriptSessionRecord(
            id = id,
            startedAt = startedAt,
            endedAt = finishedAt,
            engine = TranscriptionEngineKind.WhisperKit,
            modelVariant = modelVariant,
            inputName = null,
            segments = lines.filter { it.text.isNotBlank() }.map {
                SavedSegment(UUID.randomUUID(), it.text.trim(), null, null, startedAt + it.startSeconds, isCommitted = true)
            },
            title = title(fileName),
            languageCode = languageCode,
            fromRecording = true,
            cutShort = cutShort,
            utcOffsetSeconds = TimeZone.getDefault().getOffset((startedAt * 1_000).toLong()) / 1_000,
        )
    }

    fun lines(record: TranscriptSessionRecord): List<TranscribedLine> =
        record.segments.map { TranscribedLine(it.startTimestamp - record.startedAt, it.startTimestamp - record.startedAt, it.text) }

    private fun title(fileName: String?): String? {
        val name = fileName?.trim().orEmpty()
        val dot = name.lastIndexOf('.')
        val bare = if (dot > 0) name.substring(0, dot) else name
        return bare.trim().ifEmpty { null }
    }
}
