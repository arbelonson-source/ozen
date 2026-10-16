package com.arbelonson.ozen.core

import java.util.UUID

object FileTranscriptRecord {
    fun make(
        fileName: String?,
        lines: List<TranscribedLine>,
        startedAt: Double,
        endedAt: Double?,
        modelVariant: String?,
        id: UUID = UUID.randomUUID(),
    ): TranscriptSessionRecord = TranscriptSessionRecord(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = modelVariant,
        inputName = null,
        segments = lines.filter { it.text.isNotBlank() }.map {
            SavedSegment(UUID.randomUUID(), it.text.trim(), null, null, startedAt + it.startSeconds, isCommitted = true)
        },
        title = title(fileName),
    )

    fun lines(record: TranscriptSessionRecord): List<TranscribedLine> =
        record.segments.map { TranscribedLine(it.startTimestamp - record.startedAt, it.startTimestamp - record.startedAt, it.text) }

    private fun title(fileName: String?): String? {
        val name = fileName?.trim().orEmpty()
        val dot = name.lastIndexOf('.')
        val bare = if (dot > 0) name.substring(0, dot) else name
        return bare.trim().ifEmpty { null }
    }
}
