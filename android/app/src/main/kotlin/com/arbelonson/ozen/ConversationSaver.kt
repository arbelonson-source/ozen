package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.ConversationBreak
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps what captions say as saved conversations, by the iPhone's rules
 * (`LiveCaptionViewModel.persistHistory`): saved every twenty seconds
 * while captions run and when they stop, closed after twenty quiet
 * minutes so the next words start a new one, and old ones deleted as the
 * retention setting says, never one whose lines are still on screen.
 */
class ConversationSaver(
    private val writer: TranscriptHistoryWriter,
    private val lines: () -> List<TranscriptSegment>,
    private val isListening: () -> Boolean,
    private val settings: () -> AppSettings,
    private val transcribing: () -> AppSettings,
    private val speakerName: (TranscriptSegment) -> String?,
    private val inputName: () -> String?,
    private val startNewConversation: () -> Unit,
    private val setSaveHistory: (Boolean) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Double = { System.currentTimeMillis() / 1_000.0 },
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private var id = UUID.randomUUID()
    private var startedAt: Double? = null
    private var end: Pair<UUID, Double>? = null
    private var offset = 0
    private val closed = mutableListOf<UUID>()
    private val reading = mutableSetOf<UUID>()
    private var sawListening = false
    private var autosave: Job? = null
    private var lastRetentionCheck = 0.0

    private val currentLines: List<TranscriptSegment>
        get() = lines().let { if (offset == 0) it else it.drop(minOf(offset, it.size)) }

    private val conversationStart: Double?
        get() = ConversationBreak.start(listeningSince = startedAt, firstLineAt = currentLines.firstOrNull()?.startTimestamp)

    fun listeningChanged() {
        val listening = isListening()
        if (listening == sawListening) return
        sawListening = listening
        if (listening) {
            end = null
            checkForConversationBreak()
            if (startedAt == null) startedAt = currentLines.firstOrNull()?.startTimestamp ?: now()
            if (autosave == null) {
                autosave = scope.launch {
                    while (true) {
                        delay(AUTOSAVE_MILLIS)
                        if (!checkForConversationBreak()) save(ended = false, inBackground = true)
                    }
                }
            }
        } else {
            autosave?.cancel()
            autosave = null
            save(ended = true)
        }
    }

    /**
     * A save after captions stopped keeps the end the stop wrote; without
     * it the conversation turned open again, or ended at that later save.
     */
    fun save(ended: Boolean, endedAt: Double? = null, inBackground: Boolean = false) {
        if (!settings().saveHistory) return
        val startedAt = conversationStart ?: return
        val kept = end?.takeIf { it.first == id && !isListening() }?.second
        val endsAt = if (ended) (endedAt ?: kept ?: now()).also { end = id to it } else kept
        val using = transcribing()
        val record = TranscriptSessionRecord.make(
            from = currentLines,
            speakerName = speakerName,
            id = id,
            startedAt = startedAt,
            endedAt = endsAt,
            engine = using.engine,
            modelVariant = using.modelDescription,
            inputName = inputName(),
        )
        if (inBackground) writer.saveInBackground(record) else writer.saveNow(record)
        deleteExpiredIfDue()
    }

    /** Ends the saved conversation after a long quiet stretch; its lines stay on screen. */
    fun checkForConversationBreak(now: Double = this.now()): Boolean {
        val lastCaptionAt = currentLines.maxOfOrNull { it.lastUpdateTimestamp }
        if (!ConversationBreak.shouldStartNew(lastCaptionAt, now)) return false
        close(endedAt = lastCaptionAt)
        startNewConversation()
        return true
    }

    /**
     * Switched off, what was said until now is kept and nothing after it;
     * switched back on, saving starts with the next line. Without this the
     * next save after switching back on wrote every line said while it was off.
     */
    fun switchSaving(on: Boolean) {
        if (on == settings().saveHistory) return
        close(endedAt = currentLines.maxOfOrNull { it.lastUpdateTimestamp })
        setSaveHistory(on)
    }

    val lastSaveFailure: String? get() = writer.lastFailure

    /** Deletes what the retention setting says has expired, now, after any save in flight. */
    suspend fun deleteExpired(now: Double = this.now()): Int {
        lastRetentionCheck = now
        val retention = settings().historyRetention
        if (retention == HistoryRetention.Forever) return 0
        val onScreen = closed.toSet() + reading + id
        return withContext(io) { writer.deleteExpiredNow(retention, now, onScreen) }
    }

    private fun close(endedAt: Double?) {
        if (settings().saveHistory) {
            save(ended = true, endedAt = endedAt)
            closed += id
        }
        forgetCurrent()
    }

    /**
     * Deletes a saved conversation. If it is the one still being
     * captioned, its lines stay on screen but the next words start a new
     * conversation, so an autosave can't bring the deleted one back.
     */
    fun delete(id: UUID) {
        writer.deleteNow(id)
        closed.remove(id)
        if (id == this.id) forgetCurrent()
    }

    fun isCurrent(id: UUID): Boolean = id == this.id

    /** A conversation open for reading is not deleted as old while it is read. */
    fun opened(id: UUID) {
        reading += id
    }

    fun doneWith(id: UUID) {
        reading -= id
    }

    private fun forgetCurrent() {
        id = UUID.randomUUID()
        offset = lines().size
        startedAt = null
    }

    /** Android may end the app next: what was said since the last autosave is written now. */
    fun memoryShort() = save(ended = false)

    private fun deleteExpiredIfDue() {
        val at = now()
        if (at - lastRetentionCheck <= RETENTION_CHECK_SECONDS) return
        lastRetentionCheck = at
        val retention = settings().historyRetention
        if (retention == HistoryRetention.Forever) return
        val onScreen = closed.toSet() + reading + id
        scope.launch(io) { writer.deleteExpiredNow(retention, at, onScreen) }
    }

    private companion object {
        const val AUTOSAVE_MILLIS = 20_000L
        const val RETENTION_CHECK_SECONDS = 6 * 60 * 60.0
    }
}
