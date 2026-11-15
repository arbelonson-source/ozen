package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID

/**
 * Keeps the text of a recording that is being turned into text, so an hour of
 * work is not lost if Android ends the app while it is out of sight. The
 * lines are saved to History in small batches, as a conversation that has
 * not ended, and a marker file names it. The run that finishes replaces it
 * under the same id; a run found still marked at the next launch becomes a
 * conversation marked cut short. With saving off nothing is written.
 */
class FileTranscriptKeeper(
    private val store: TranscriptHistoryStore,
    private val marker: File,
    private val historyOn: () -> Boolean,
    private val now: () -> Double = { System.currentTimeMillis() / 1_000.0 },
    private val everyLines: Int = 5,
    private val everySeconds: Double = 30.0,
) {
    class Interrupted(val id: UUID, val name: String?)

    var id: UUID = UUID.randomUUID()
        private set

    private var fileName: String? = null
    private var recordingSeconds: Double? = null
    private var modelVariant: String? = null
    private var languageCode: String? = null
    private var began = 0.0
    private var keptLines = 0
    private var lastKept = 0.0

    fun begin(fileName: String?, recordingSeconds: Double?, modelVariant: String?, languageCode: String?) {
        id = UUID.randomUUID()
        this.fileName = fileName
        this.recordingSeconds = recordingSeconds
        this.modelVariant = modelVariant
        this.languageCode = languageCode
        began = now()
        keptLines = 0
        lastKept = began
    }

    /** Every line so far; saves when enough are new, or enough time has passed since the last save. */
    fun linesSoFar(lines: List<TranscribedLine>) {
        val fresh = lines.size - keptLines
        if (!historyOn()) {
            dropKept()
            return
        }
        if (fresh <= 0) return
        val due = fresh >= everyLines || now() - lastKept >= everySeconds
        if (!due) return
        val reach = maxOf(recordingSeconds ?: 0.0, lines.maxOf { it.endSeconds })
        val whole = FileTranscriptRecord.make(fileName, lines, began + reach, recordingSeconds, modelVariant, id, languageCode)
        try {
            if (!marker.exists()) PrivateFileWrites.write(marker, id.toString().toByteArray(Charsets.UTF_8))
            if (store.save(whole.copy(endedAt = null), updateSearchCaches = false, throttleCaches = true)) {
                keptLines = lines.size
                lastKept = now()
            }
        } catch (_: Exception) {
        }
    }

    /** The finished conversation has been saved under [id]: nothing is left to recover. */
    fun finished() {
        if (!historyOn()) dropKept()
        marker.delete()
    }

    /** History was switched off meanwhile: the unfinished copy it already holds goes, as nothing is to be kept. */
    private fun dropKept() {
        if (keptLines == 0 && !marker.exists()) return
        runCatching { store.delete(id) }
        marker.delete()
        keptLines = 0
    }

    /** The run ended without text worth keeping (stopped, or failed): what was kept goes. */
    fun discard() {
        if (marker.exists()) runCatching { store.delete(id) }
        marker.delete()
    }

    fun recoverInterrupted(): Interrupted? {
        val text = runCatching { marker.readText().trim() }.getOrNull() ?: return null
        val kept = runCatching { UUID.fromString(text) }.getOrNull()
        if (!historyOn()) {
            kept?.let { runCatching { store.delete(it) } }
            marker.delete()
            return null
        }
        val record = kept?.let { runCatching { store.load(it) }.getOrNull() }
        if (record == null || record.endedAt != null) {
            marker.delete()
            return null
        }
        val endedAt = record.segments.maxOfOrNull { it.startTimestamp } ?: record.startedAt
        val saved = runCatching { store.save(record.copy(endedAt = endedAt, cutShort = true)) }.getOrDefault(false)
        if (saved) marker.delete()
        return if (saved) Interrupted(record.id, record.title) else null
    }
}
