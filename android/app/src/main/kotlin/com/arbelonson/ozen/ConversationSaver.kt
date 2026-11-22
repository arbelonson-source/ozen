package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.ConversationBreak
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.HistorySave
import com.arbelonson.ozen.core.ProblemAudioStore
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.WordFix
import com.arbelonson.ozen.core.WordFixResult
import com.arbelonson.ozen.core.WordFixes
import java.io.IOException
import java.util.TreeMap
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
    /**
     * The language the running captions were started in. A change of the
     * setting reaches the settings before the captions restart, and a save
     * in between must not label the old words with the new language.
     */
    private val spokenLanguage: (() -> String)? = null,
    /** The phone's offset from UTC at a moment: a conversation keeps the one from when it started. */
    private val utcOffsetAt: (Double) -> Int = { at -> java.util.TimeZone.getDefault().getOffset((at * 1_000).toLong()) / 1_000 },
    /** Runs a step on the main thread, where the lines on screen change; History asks from a thread of its own. */
    private val onMainThread: (() -> Unit) -> Unit = { it() },
) {
    private var id = UUID.randomUUID()
    private var startedAt: Double? = null
    private var end: Pair<UUID, Double>? = null
    private var offset = 0
    private val closed = linkedMapOf<UUID, Closed>()

    /** Which of [closed] holds each line, made again after one closes or goes. */
    private var closedLines: ClosedLines? = null

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
    fun save(ended: Boolean, endedAt: Double? = null, inBackground: Boolean = false): TranscriptSessionRecord? =
        write(ended, endedAt, inBackground)?.first

    /** [save], and whether this conversation reached the disk: another one still waiting for room may fail meanwhile. */
    private fun write(ended: Boolean, endedAt: Double?, inBackground: Boolean): Pair<TranscriptSessionRecord, Boolean>? {
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
            languageCode = spokenLanguage?.invoke() ?: using.languageCode,
            utcOffsetSeconds = utcOffsetAt(startedAt),
            timeZoneId = java.time.ZoneId.systemDefault().id,
        )
        val written = if (inBackground) {
            writer.saveInBackground(record)
            true
        } else {
            writer.saveNow(record)
        }
        deleteExpiredIfDue()
        return record to written
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
        for (conversation in closed.values) writer.saveInBackground(closedRecord(conversation, all) ?: continue)
        save(ended = false, inBackground = true)
    }

    /** A conversation a long quiet closed, as its lines still on screen show it now, with the times and labels it was closed with. */
    private fun closedRecord(conversation: Closed, all: List<TranscriptSegment> = lines()): TranscriptSessionRecord? {
        val record = conversation.record ?: return null
        val from = all.subList(minOf(conversation.lines.first, all.size), minOf(conversation.lines.last + 1, all.size))
        return TranscriptSessionRecord.make(
            from = from,
            speakerName = speakerName,
            id = record.id,
            startedAt = record.startedAt,
            endedAt = record.endedAt,
            engine = record.engine,
            modelVariant = record.modelVariant,
            inputName = record.inputName,
            starred = starred.toSet(),
            languageCode = record.languageCode,
            utcOffsetSeconds = record.utcOffsetSeconds,
            timeZoneId = record.timeZoneId,
        )
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

    /** Off is the reader's choice. Failed is the phone refusing the write: it stays queued for another try, but it is not on disk yet. */
    fun saveRecording(record: TranscriptSessionRecord): HistorySave = when {
        !settings().saveHistory -> HistorySave.Off
        writer.saveNow(record) -> HistorySave.Saved(record.id)
        else -> HistorySave.Failed
    }

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
            closedLines = null
        }
        forgetCurrent()
    }

    /**
     * Deletes a saved conversation. If it is the one still being
     * captioned, its lines stay on screen but the next words start a new
     * conversation, so an autosave can't bring the deleted one back.
     *
     * History asks from its own thread, while the caption screen reads at
     * every word, on the main thread, which conversation each line belongs
     * to. So what is kept here changes in one step of the main thread, and
     * only then is the file deleted, after every save of it the main thread
     * queued before that step. Changed on History's thread, a word landing
     * meanwhile closed the app, or kept naming the deleted conversation for
     * its lines.
     */
    fun delete(id: UUID) {
        onMainThread {
            closed.remove(id)
            closedLines = null
            if (id == this.id) forgetCurrent()
        }
        writer.deleteNow(id)
    }

    /**
     * "Clear captions from the screen", as on the iPhone: what was said is
     * saved as one ended conversation before [clearLines] takes the lines
     * away, and the next words start a new one from the top of the screen.
     * When the question promised the lines stay in History
     * ([keepingHistory]) and that save fails, nothing is cleared and false
     * comes back, so the question can be asked again, now saying saving
     * isn't working.
     */
    fun clear(keepingHistory: Boolean = false, clearLines: () -> Unit): Boolean {
        if (settings().saveHistory) {
            val saved = save(ended = true)
            if (keepingHistory && saved != null && writer.lastFailure != null) return false
        }
        clearLines()
        closed.clear()
        closedLines = null
        starred.clear()
        id = UUID.randomUUID()
        offset = 0
        startedAt = if (isListening()) now() else null
        return true
    }

    /**
     * Deletes every saved conversation; the lines on screen stay, and the
     * next words start a new one. Asked from History's thread, as [delete]
     * is. The deletion is handed to the writer in the same main-thread step
     * that starts the next conversation, and waited for here: handed in
     * after that step, a save of the next conversation the main thread made
     * meanwhile was deleted with the rest, and with captions stopped no
     * later save brought it back.
     */
    fun deleteAll() {
        var deleted: () -> Unit = {}
        onMainThread {
            closed.clear()
            closedLines = null
            forgetCurrent()
            deleted = writer.deleteAllInTurn()
        }
        deleted()
    }

    /**
     * Names a saved conversation, or takes its name away with a blank one,
     * queued with the saves so an autosave of the one still being captioned
     * can't write it back without the name.
     */
    fun rename(id: UUID, title: String): Boolean = writer.renameNow(id, title)

    /** A saved conversation as History shows it: as it will be saved, with what still waits for room (see [TranscriptHistoryWriter.loadNow]). Throws when its file is there but can't be read just now. */
    @Throws(IOException::class)
    fun load(id: UUID): TranscriptSessionRecord? = writer.loadNow(id)

    /**
     * Stars a saved line from History, or takes its star away; the line's
     * new state, or null when it is gone or could not be saved. A line of
     * the conversation still being captioned keeps its star here, since
     * every autosave writes that conversation afresh, and it is saved at
     * once so it reads back starred. When that save fails the star goes back
     * to what History shows, and is saved that way, so the waiting save and
     * a second tap don't bring back a star the person was told was not
     * changed. A line of that conversation that is no longer on screen (a
     * replay heard that speech again and took the line off) is refused, as
     * [fixLine] refuses a fix of it: every save of that conversation is
     * written from the lines on screen, and the next one dropped a star
     * written to its file alone, with the line.
     *
     * [to], when given, is the state the line takes, whichever conversation
     * it is in; a line being captioned that has it already is still saved,
     * so its file follows. The starred lines list shows the stars of the
     * files, which a version waiting for room or the caption screen can
     * differ from (a star taken off there while the phone was full), and its
     * Remove the star, as a tap, put the star back on such a line.
     */
    fun toggleStar(sessionID: UUID, segmentID: UUID, to: Boolean? = null): Boolean? {
        if (sessionID != id) {
            return writer.toggleStarNow(sessionID, segmentID, keepingRefused = false, to = to)?.also { mark(segmentID, it) }
        }
        if (currentLines.none { it.id == segmentID }) return null
        val had = segmentID in starred
        val (starring, written) = starCurrentLine(segmentID, to ?: !had)
        if (!written) {
            starCurrentLine(segmentID, had)
            return null
        }
        return starring
    }

    /** Gives a line of the conversation being captioned [starring] and saves it: its new state, and whether the save reached the disk. */
    private fun starCurrentLine(segmentID: UUID, starring: Boolean = segmentID !in starred): Pair<Boolean, Boolean> {
        mark(segmentID, starring)
        return starring to (write(ended = false, endedAt = null, inBackground = false)?.second ?: true)
    }

    fun fixLiveLine(segmentID: UUID, text: String) {
        if (currentLines.any { it.id == segmentID }) {
            save(ended = false)
            return
        }
        for (conversation in closed.keys.reversed()) if (writer.fixTextNow(conversation, segmentID, text)) return
    }

    /**
     * Fixes a word of a saved line, in the text the line has now. A line of
     * the conversation being captioned is fixed on screen by [onScreen],
     * which says whether the line changed, and only then saved. The screen
     * decides in its own step, as it fixes the line: the engine can rewrite
     * the line before the fix gets there, so a copy read here first could
     * call a fix that changed nothing done, or one that did refused. A line
     * of an earlier conversation is changed in its file, whether or not its
     * lines are still on screen, and a fix that must wait for room counts
     * as saved (see [TranscriptHistoryWriter.fixTextNow]). A line that no
     * longer has the word is refused: History's dialog keeps the words the
     * line had when it opened, and such a fix changed nothing yet closed the
     * dialog as done and was kept for next time. So is a line of the
     * conversation being captioned that is no longer on screen (a replay
     * heard that speech again and took the line off): every save of that
     * conversation is written from the lines on screen, and the next one
     * dropped a fix written to its file alone, with the line.
     *
     * History asks from its own thread, so which conversation the line
     * belongs to, the screen's fix and what is saved are all decided in one
     * step of the main thread: read on History's thread afterwards, a long
     * quiet, a clear or a new caption landing in between paired the fix
     * with the wrong conversation or the wrong lines. A line a long quiet
     * closed before that step is an earlier conversation's.
     */
    fun fixLine(sessionID: UUID, segmentID: UUID, fix: WordFix, onScreen: (UUID, WordFix) -> Boolean): WordFixResult {
        var took: Boolean? = null
        onMainThread { took = fixOnScreen(sessionID, segmentID, fix, onScreen) }
        val saved = took ?: return writer.fixTextNow(sessionID, segmentID) { WordFixes.apply(listOf(fix), it) }
        writer.waitUntilIdle()
        return if (saved) WordFixResult.Saved else WordFixResult.LineChanged
    }

    /**
     * [fixLine]'s step on the main thread: null for a line of an earlier
     * conversation, else whether the screen fixed the line, whose
     * conversation is then queued to be saved, as the screen shows it now,
     * before anything else the main thread saves.
     */
    private fun fixOnScreen(sessionID: UUID, segmentID: UUID, fix: WordFix, onScreen: (UUID, WordFix) -> Boolean): Boolean? {
        if (sessionID != id) return null
        if (currentLines.none { it.id == segmentID } || !onScreen(segmentID, fix)) return false
        if (sessionID == id) {
            write(ended = false, endedAt = null, inBackground = true)
            return true
        }
        writer.saveInBackground(closed[sessionID]?.let { closedRecord(it) } ?: return false)
        return true
    }

    /**
     * History's fix of a word: [fixLine], and a line of an earlier
     * conversation still on screen takes the fix there too, unless the
     * screen was asked already. Whether the line's conversation is still the
     * one being captioned can change right after the screen's step (a long
     * quiet), and asking again then fixed its words a second time.
     */
    fun fixLineEverywhere(sessionID: UUID, segmentID: UUID, fix: WordFix, onScreen: (UUID, WordFix) -> Boolean): WordFixResult {
        var asked = false
        val result = fixLine(sessionID, segmentID, fix) { line, change ->
            asked = true
            onScreen(line, change)
        }
        if (result == WordFixResult.Saved && !asked) onMainThread { onScreen(segmentID, fix) }
        return result
    }

    /**
     * Stars a line on the caption screen, or takes its star away, in the
     * conversation it belongs to: the one being captioned, or one closed by
     * a long quiet whose lines are still on screen. With saving off the star
     * still shows on the line.
     */
    fun toggleLiveStar(segmentID: UUID): Boolean {
        if (currentLines.any { it.id == segmentID }) return starCurrentLine(segmentID).first
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

    val currentId: UUID get() = id

    /**
     * The saved conversation a caption line belongs to, by its place among
     * the lines: the one being captioned or one a long quiet closed. None
     * for a line said while saving was off, or while saves are failing.
     */
    fun savedConversation(index: Int): UUID? {
        if (!settings().saveHistory || writer.lastFailure != null) return null
        return conversationOfLine(index)
    }

    /**
     * The caption screen asks this for each line it draws at every word.
     * A phone listening for days keeps every conversation a long quiet
     * closed while their lines stay on screen, and asking each of them in
     * turn took a phone's word longer with every one.
     */
    fun conversationOfLine(index: Int): UUID? {
        if (index !in lines().indices) return null
        if (index >= offset) return id
        val lookup = closedLines ?: ClosedLines(
            closed.map { (id, conversation) ->
                whileReadingClosed?.invoke()
                id to conversation.lines
            },
        ).also { closedLines = it }
        return lookup.at(index)
    }

    /** Test-only: runs as the table of which conversation holds each line reads each one a long quiet closed. */
    internal var whileReadingClosed: (() -> Unit)? = null

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

/**
 * Which of the conversations a long quiet closed holds a line, given in
 * the order they closed, found by halving rather than by asking each in
 * turn. Two of them hold the same line only when lines left the screen in
 * between; the later one has it, as before. Calls come for the drawn lines
 * in order, so the stretch found last is tried first, then the one after it.
 */
internal class ClosedLines(conversations: List<Pair<UUID, IntRange>>) {
    private val starts: IntArray
    private val ends: IntArray
    private val ids: Array<UUID>
    private var lastFound = -1

    init {
        // From the latest back, each takes the lines no later one holds.
        val taken = TreeMap<Int, Int>()
        val owners = HashMap<Int, UUID>()
        for ((id, lines) in conversations.asReversed()) {
            var from = lines.first
            while (from <= lines.last) {
                val before = taken.floorEntry(from)
                if (before != null && before.value >= from) {
                    from = before.value + 1
                    continue
                }
                val after = taken.ceilingKey(from)
                val to = if (after == null) lines.last else minOf(lines.last, after - 1)
                taken[from] = to
                owners[from] = id
                from = to + 1
            }
        }
        starts = taken.keys.toIntArray()
        ends = taken.values.toIntArray()
        ids = Array(starts.size) { owners.getValue(starts[it]) }
    }

    fun at(index: Int): UUID? {
        val last = lastFound
        if (last >= 0 && index >= starts[last] && index <= ends[last]) return ids[last]
        val next = last + 1
        if (last >= 0 && next < starts.size && index >= starts[next] && index <= ends[next]) {
            lastFound = next
            return ids[next]
        }
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                index < starts[middle] -> high = middle - 1
                index > ends[middle] -> low = middle + 1
                else -> {
                    lastFound = middle
                    return ids[middle]
                }
            }
        }
        return null
    }
}
