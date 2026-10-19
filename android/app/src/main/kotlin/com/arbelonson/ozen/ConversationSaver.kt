package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.ConversationBreak
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.ProblemAudioStore
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
    private val problemAudio: ProblemAudioStore? = null,
) {
    private var id = UUID.randomUUID()
    private var startedAt: Double? = null
    private var end: Pair<UUID, Double>? = null
    private var offset = 0
    private val closed = linkedMapOf<UUID, Closed>()

    /** A conversation a long quiet ended while its lines stay on screen, and how it was last written. */
    private class Closed(val lines: IntRange, val record: TranscriptSessionRecord?)
    private val reading = mutableSetOf<UUID>()
    private val starred = mutableSetOf<UUID>()
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
    fun save(ended: Boolean, endedAt: Double? = null, inBackground: Boolean = false): TranscriptSessionRecord? {
        if (!settings().saveHistory) return null
        val startedAt = conversationStart ?: return null
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
            starred = starred.toSet(),
        )
        if (inBackground) writer.saveInBackground(record) else writer.saveNow(record)
        deleteExpiredIfDue()
        return record
    }

    /**
     * A voice was named or its name changed: the conversations whose lines
     * are on screen are written again with the names they show now, as on
     * the iPhone. The open one's autosave would catch up, but one a long
     * quiet had closed kept the old names in History for good.
     */
    fun speakerLabelsChanged() {
        if (!settings().saveHistory) return
        val all = lines()
        for (conversation in closed.values) {
            val record = conversation.record ?: continue
            val from = all.subList(minOf(conversation.lines.first, all.size), minOf(conversation.lines.last + 1, all.size))
            writer.saveInBackground(
                TranscriptSessionRecord.make(
                    from = from,
                    speakerName = speakerName,
                    id = record.id,
                    startedAt = record.startedAt,
                    endedAt = record.endedAt,
                    engine = record.engine,
                    modelVariant = record.modelVariant,
                    inputName = record.inputName,
                    starred = starred.toSet(),
                ),
            )
        }
        save(ended = false, inBackground = true)
    }

    /** A saved person's name changed: every saved conversation follows, not only those on screen. */
    fun renameSpeaker(from: String, to: String) = writer.renameSpeakerInBackground(from, to)

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

    fun saveRecording(record: TranscriptSessionRecord): Boolean = writer.saveNow(record)

    /** Deletes what the retention setting says has expired, now, after any save in flight. */
    suspend fun deleteExpired(now: Double = this.now()): Int {
        lastRetentionCheck = now
        val retention = settings().historyRetention
        if (retention == HistoryRetention.Forever) return 0
        val onScreen = closed.keys + reading + id
        return withContext(io) {
            deleteOldClips(retention, now)
            writer.deleteExpiredNow(retention, now, onScreen)
        }
    }

    private fun close(endedAt: Double?) {
        if (settings().saveHistory) {
            closed[id] = Closed(offset until lines().size, save(ended = true, endedAt = endedAt))
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

    /**
     * "Clear captions from the screen", as on the iPhone: what was said is
     * saved as one ended conversation before [clearLines] takes the lines
     * away, and the next words start a new one from the top of the screen.
     */
    fun clear(clearLines: () -> Unit) {
        if (settings().saveHistory) save(ended = true)
        clearLines()
        closed.clear()
        starred.clear()
        id = UUID.randomUUID()
        offset = 0
        startedAt = if (isListening()) now() else null
    }

    /** Deletes every saved conversation; the lines on screen stay, and the next words start a new one. */
    fun deleteAll() {
        writer.deleteAllNow()
        closed.clear()
        forgetCurrent()
    }

    /**
     * Names a saved conversation, or takes its name away with a blank one,
     * queued with the saves so an autosave of the one still being captioned
     * can't write it back without the name.
     */
    fun rename(id: UUID, title: String) = writer.renameNow(id, title)

    /**
     * Stars a saved line, or takes its star away; the line's new state, or
     * null when it is gone. A line of the conversation still being captioned
     * keeps its star here, since every autosave writes that conversation
     * afresh, and it is saved at once so it reads back starred.
     */
    fun toggleStar(sessionID: UUID, segmentID: UUID): Boolean? {
        if (sessionID != id || currentLines.none { it.id == segmentID }) {
            return writer.toggleStarNow(sessionID, segmentID)?.also { mark(segmentID, it) }
        }
        val starring = segmentID !in starred
        mark(segmentID, starring)
        save(ended = false)
        return starring
    }

    /**
     * Stars a line on the caption screen, or takes its star away, in the
     * conversation it belongs to: the one being captioned, or one closed by
     * a long quiet whose lines are still on screen. With saving off the star
     * still shows on the line.
     */
    fun toggleLiveStar(segmentID: UUID): Boolean {
        if (currentLines.any { it.id == segmentID }) return toggleStar(id, segmentID) ?: false
        for (conversation in closed.keys.reversed()) {
            writer.toggleStarNow(conversation, segmentID)?.let { starring ->
                mark(segmentID, starring)
                return starring
            }
        }
        val starring = segmentID !in starred
        mark(segmentID, starring)
        return starring
    }

    fun isStarred(segmentID: UUID): Boolean = segmentID in starred

    private fun mark(segmentID: UUID, starring: Boolean) {
        if (starring) starred += segmentID else starred -= segmentID
    }

    fun isCurrent(id: UUID): Boolean = id == this.id

    /**
     * The saved conversation a caption line belongs to, by its place among
     * the lines: the one being captioned or one a long quiet closed. None
     * for a line said while saving was off, or while saves are failing.
     */
    fun savedConversation(index: Int): UUID? {
        if (!settings().saveHistory || writer.lastFailure != null || index !in lines().indices) return null
        if (index >= offset) return id
        return closed.entries.lastOrNull { index in it.value.lines }?.key
    }

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
    fun memoryShort() {
        save(ended = false)
    }

    /** After each save, and as the app comes back: with saving off nothing else sweeps. */
    fun deleteExpiredIfDue() {
        val at = now()
        if (at - lastRetentionCheck <= RETENTION_CHECK_SECONDS) return
        lastRetentionCheck = at
        val retention = settings().historyRetention
        if (retention == HistoryRetention.Forever) return
        val onScreen = closed.keys + reading + id
        scope.launch(io) {
            deleteOldClips(retention, at)
            writer.deleteExpiredNow(retention, at, onScreen)
        }
    }

    /** A marked problem's sound is her voice: it keeps no longer than her conversations do. */
    private fun deleteOldClips(retention: HistoryRetention, now: Double) {
        retention.cutoff(now)?.let { problemAudio?.deleteClips(olderThan = it) }
    }

    private companion object {
        const val AUTOSAVE_MILLIS = 20_000L
        const val RETENTION_CHECK_SECONDS = 6 * 60 * 60.0
    }
}
