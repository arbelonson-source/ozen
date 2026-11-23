package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.ChunkedList
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
    /**
     * The lines on screen. The autosave makes its record from them on the
     * writer's thread, so a list that may change later is copied first; the
     * caption pipeline's never changes once given out, and is taken as it is.
     */
    private val lines: () -> List<TranscriptSegment>,
    private val isListening: () -> Boolean,
    private val settings: () -> AppSettings,
    private val transcribing: () -> AppSettings,
    private val speakerName: (TranscriptSegment) -> String?,
    private val inputName: () -> String?,
    private val startNewConversation: () -> Unit,
    private val setSaveHistory: (Boolean) -> Unit = {},
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
    /**
     * [speakerName] as the voices are named at this moment, for the autosave
     * to read on the writer's thread while the screen names voices on the
     * main thread. [speakerName] itself when its names never change.
     */
    private val speakerNames: () -> (TranscriptSegment) -> String? = { speakerName },
    /**
     * [setSaveHistory] in two steps, for History's switch: the setting
     * changes when this is called, on the main thread, and the function
     * returned writes it, off that thread. [setSaveHistory] itself, with
     * nothing left to write, when the setting is kept only in memory.
     */
    private val saveHistoryInTurn: (Boolean) -> () -> Unit = { on ->
        setSaveHistory(on)
        val nothingToWrite: () -> Unit = {}
        nothingToWrite
    },
    /**
     * Runs on the main thread once a save the caption screen handed to the
     * writer without waiting for it is written or refused, so a refused one
     * still shows the saving warning, as it did while the screen waited.
     */
    private val saveDone: () -> Unit = {},
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

    /** How many times each line's star was given or taken away: a star History could not save is taken back only while it is still the line's newest. */
    private val starTurns = HashMap<UUID, Int>()
    private var sawListening = false
    private var autosave: Job? = null
    private var lastRetentionCheck = 0.0

    /** How many times the screen was cleared: a clear still waiting for its save does nothing once another cleared the screen first. */
    private var clears = 0

    private val currentLines: List<TranscriptSegment>
        get() = shownSince(lines())

    private fun shownSince(all: List<TranscriptSegment>): List<TranscriptSegment> =
        if (offset == 0) all else all.subList(minOf(offset, all.size), all.size)

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
                        if (!checkForConversationBreak()) saveInBackground()
                    }
                }
            }
        } else {
            autosave?.cancel()
            autosave = null
            writeInTurn(ended = true, endedAt = null)?.let { whenWritten(it.second) }
        }
    }

    /**
     * Hears back from [written], a save handed to the writer that the main
     * thread does not wait for: waited for there, a phone slow to write
     * froze the captions until it was in. Once it is done, [then] runs on
     * the main thread with whether it reached the disk with nothing else
     * refused, and then [saveDone].
     */
    private fun whenWritten(written: () -> Boolean = { true }, then: (Boolean) -> Unit = {}) {
        writer.whenWritten {
            val reached = try {
                written()
            } catch (_: Exception) {
                false
            }
            val kept = reached && writer.lastFailure == null
            scope.launch {
                then(kept)
                saveDone()
            }
        }
    }

    /**
     * A save after captions stopped keeps the end the stop wrote; without
     * it the conversation turned open again, or ended at that later save.
     */
    fun save(ended: Boolean, endedAt: Double? = null): TranscriptSessionRecord? = write(ended, endedAt)?.first

    /** [save], and whether this conversation reached the disk: another one still waiting for room may fail meanwhile. */
    private fun write(ended: Boolean, endedAt: Double?): Pair<TranscriptSessionRecord, Boolean>? =
        writeInTurn(ended, endedAt)?.let { (record, written) -> record to written() }

    /** [write] handed to the writer now; the function returned waits for it. */
    private fun writeInTurn(ended: Boolean, endedAt: Double?): Pair<TranscriptSessionRecord, () -> Boolean>? {
        val record = recording(ended, endedAt, speakerName)?.invoke() ?: return null
        val written = writer.saveInTurn(record)
        deleteExpiredIfDue()
        return record to written
    }

    /**
     * Saves the conversation being captioned without holding up the screen:
     * what it is made of is read here, and its record is made on the
     * writer's thread, in turn with the other saves. Made here, a whole
     * day's lines held up the captions on a slow phone every twenty seconds.
     */
    private fun saveInBackground() {
        val record = recording(ended = false, endedAt = null, names = speakerNames()) ?: return
        writer.saveInBackground(record)
        deleteExpiredIfDue()
    }

    /**
     * What a save of the conversation being captioned is made of, read now:
     * the record is made when the function returned is called, on any
     * thread, from nothing that can change meanwhile.
     */
    private fun recording(ended: Boolean, endedAt: Double?, names: (TranscriptSegment) -> String?): (() -> TranscriptSessionRecord)? {
        if (!settings().saveHistory) return null
        val shown = shownSince(lines().let { if (it is ChunkedList<TranscriptSegment>) it else it.toList() })
        val startedAt = ConversationBreak.start(listeningSince = this.startedAt, firstLineAt = shown.firstOrNull()?.startTimestamp) ?: return null
        val kept = end?.takeIf { it.first == id && !isListening() }?.second
        val endsAt = if (ended) (endedAt ?: kept ?: now()).also { end = id to it } else kept
        val using = transcribing()
        val conversation = id
        val engine = using.engine
        val model = using.modelDescription
        val input = inputName()
        val stars = starred.toSet()
        val language = spokenLanguage?.invoke() ?: using.languageCode
        val utcOffset = utcOffsetAt(startedAt)
        val zone = java.time.ZoneId.systemDefault().id
        return {
            TranscriptSessionRecord.make(
                from = shown,
                speakerName = names,
                id = conversation,
                startedAt = startedAt,
                endedAt = endsAt,
                engine = engine,
                modelVariant = model,
                inputName = input,
                starred = stars,
                languageCode = language,
                utcOffsetSeconds = utcOffset,
                timeZoneId = zone,
            )
        }
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
        saveInBackground()
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

    /**
     * Ends the saved conversation after a long quiet stretch; its lines stay
     * on screen. It is closed and its save handed to the writer in this one
     * step of the main thread, so every save and History action asked after
     * it lands after that save, and the main thread does not wait for it:
     * waited for, a phone slow to write froze the captions while it was in.
     */
    fun checkForConversationBreak(now: Double = this.now()): Boolean {
        val lastCaptionAt = currentLines.maxOfOrNull { it.lastUpdateTimestamp }
        if (!ConversationBreak.shouldStartNew(lastCaptionAt, now)) return false
        closeInTurn(endedAt = lastCaptionAt)?.let { whenWritten(it) }
        startNewConversation()
        return true
    }

    /**
     * Switched off, what was said until now is kept and nothing after it;
     * switched back on, saving starts with the next line. Without this the
     * next save after switching back on wrote every line said while it was off.
     *
     * History switches on the main thread, and a phone can be slow to write.
     * The conversation is closed and handed to the writer, and the setting
     * changed, in this one step, so no line said after it is saved, and
     * every save and every History action asked after it lands after the
     * conversation is written. The function returned waits for that write
     * and then writes the setting, off the main thread: waited for on it, a
     * slow write froze the screen.
     */
    fun switchSaving(on: Boolean): () -> Unit {
        if (on == settings().saveHistory) return {}
        val closing = closeInTurn(endedAt = currentLines.maxOfOrNull { it.lastUpdateTimestamp })
        val stored = saveHistoryInTurn(on)
        return {
            closing?.invoke()
            stored()
        }
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

    /**
     * Closes the conversation being captioned, its lines kept on screen,
     * with its save handed to the writer now; the function returned waits
     * for that save, null when there was nothing to save.
     */
    private fun closeInTurn(endedAt: Double?): (() -> Boolean)? {
        var written: (() -> Boolean)? = null
        if (settings().saveHistory) {
            val saving = writeInTurn(ended = true, endedAt = endedAt)
            closed[id] = Closed(offset until lines().size, saving?.first)
            closedLines = null
            written = saving?.second
        }
        forgetCurrent()
        return written
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
     * ([keepingHistory]) and that save fails, nothing is cleared, so the
     * question can be asked again, now saying saving isn't working.
     *
     * The main thread draws the captions, and a phone can be slow to write:
     * the save is handed to the writer in this step and not waited for.
     * Without a promise, or with no line of this conversation to keep, the
     * lines go at once and true comes back. With one,
     * false comes back, the lines stay until the save is in, and [answered]
     * then runs on the main thread: true once the lines went, with any line
     * said meanwhile saved with the rest first (see [clearOnceKept]), false
     * when they stay. Should
     * another clear empty the screen first, this one has nothing left to
     * clear and answers nothing: a second Delete tapped while the first was
     * written took away, once answered, the lines said after the first.
     */
    fun clear(keepingHistory: Boolean = false, answered: (Boolean) -> Unit = {}, clearLines: () -> Unit): Boolean {
        val saving = if (settings().saveHistory) writeInTurn(ended = true, endedAt = null) else null
        if (saving == null || !keepingHistory || saving.first.segments.isEmpty()) {
            cleared(clearLines)
            saving?.let { whenWritten(it.second) }
            return true
        }
        clearOnceKept(clears, saving.first, saving.second, answered, clearLines)
        return false
    }

    /**
     * A promised clear's wait for [written], the save of [saved]: once it is
     * in, the lines go if the screen shows nothing [saved] lacks. A line said
     * or changed meanwhile is saved too and waited for the same way, as many
     * times as it takes, so no line leaves the screen before it is on the
     * disk; one refused keeps the lines. Cleared after the first save with
     * the next only handed in, a line said meanwhile was lost when the phone
     * refused it. [asked] is how many clears there were when it was asked.
     */
    private fun clearOnceKept(asked: Int, saved: TranscriptSessionRecord, written: () -> Boolean, answered: (Boolean) -> Unit, clearLines: () -> Unit) {
        whenWritten(written) { kept ->
            if (asked != clears) return@whenWritten
            if (!kept) return@whenWritten answered(false)
            val endedAt = end?.takeIf { it.first == id }?.second
            val now = recording(ended = true, endedAt = endedAt, names = speakerName)?.invoke()
            if (now == null || now == saved) {
                cleared(clearLines)
                answered(true)
                return@whenWritten
            }
            clearOnceKept(asked, now, writer.saveInTurn(now), answered, clearLines)
        }
    }

    /** The lines go, and the next words start a new conversation from the top of the screen. */
    private fun cleared(clearLines: () -> Unit) {
        clearLines()
        closed.clear()
        closedLines = null
        starred.clear()
        id = UUID.randomUUID()
        offset = 0
        startedAt = if (isListening()) now() else null
        clears += 1
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
     *
     * History asks from its own thread, and a phone can be slow to write.
     * Which conversation the line is in, its star on screen and the save of
     * the conversation being captioned are decided and handed to the writer
     * in steps of the main thread, which changes the lines at every word;
     * only the wait for the disk is History's. Waited for on the main
     * thread, a slow write froze the screen. A line a long quiet closed,
     * still on screen, is starred the same way, on screen and handed to the
     * writer in one step: starred in its file from History's thread first
     * and only then on screen, a voice named in between saved the
     * conversation from the screen, after the star and without it.
     */
    fun toggleStar(sessionID: UUID, segmentID: UUID, to: Boolean? = null): Boolean? {
        var earlier = false
        var asked: HistoryStar? = null
        onMainThread {
            val shown = if (sessionID == id) currentLines.any { it.id == segmentID } else closedShows(sessionID, segmentID)
            earlier = sessionID != id && !shown
            if (shown) {
                val had = segmentID in starred
                val starring = to ?: !had
                val written = starInTurn(sessionID, segmentID, starring)
                asked = HistoryStar(starring, had, written, starTurns.getValue(segmentID))
            }
        }
        if (earlier) {
            val starring = writer.toggleStarNow(sessionID, segmentID, keepingRefused = false, to = to) ?: return null
            onMainThread { mark(segmentID, starring) }
            return starring
        }
        val star = asked ?: return null
        if (star.written()) return star.starring
        var undone: () -> Boolean = { true }
        onMainThread { if (starTurns[segmentID] == star.turn) undone = starInTurn(sessionID, segmentID, star.had, undoing = true) }
        undone()
        return null
    }

    /**
     * A star History gave a line on screen: its new state, the one it had,
     * the wait for its save, and its turn among the line's stars. A star
     * given on the caption screen since is newer, and has its own save.
     */
    private class HistoryStar(val starring: Boolean, val had: Boolean, val written: () -> Boolean, val turn: Int)

    /** Whether [segmentID] is on screen among the lines of [sessionID], a conversation a long quiet closed, whose saves are made from the screen. */
    private fun closedShows(sessionID: UUID, segmentID: UUID): Boolean {
        val conversation = closed[sessionID]?.takeIf { it.record != null } ?: return false
        val all = lines()
        return all.subList(minOf(conversation.lines.first, all.size), minOf(conversation.lines.last + 1, all.size)).any { it.id == segmentID }
    }

    /**
     * [toggleStar]'s step on the main thread: gives the line [starring] and
     * hands the writer its save. The conversation being captioned is saved
     * as the screen shows it now. In any other, the line alone takes the
     * star in its file, in turn: saved whole from the screen, a conversation
     * a long quiet closed lost a word History had fixed in it a moment
     * before, which reached the screen only after its file. Marked here,
     * every later save of it from the screen keeps the star.
     *
     * [undoing] takes back a star the phone refused, in the file and in any
     * version waiting for room, and waits for room itself when nothing else
     * does: Clear captions may have saved the conversation with the star
     * meanwhile, and taken back only on the screen, a star History said was
     * not saved reached the file once there was room.
     */
    private fun starInTurn(sessionID: UUID, segmentID: UUID, starring: Boolean, undoing: Boolean = false): () -> Boolean {
        mark(segmentID, starring)
        if (sessionID == id) return writeInTurn(ended = false, endedAt = null)?.second ?: { true }
        val saved = writer.toggleStarInTurn(sessionID, segmentID, keepingRefused = undoing, to = starring)
        return { saved() != null }
    }

    /**
     * A word fixed on the caption screen: [text] is the line's words now. A
     * line of the conversation being captioned saves that conversation; one
     * a long quiet closed is written to the latest of those conversations
     * that has it. Handed to the writer and not waited for: waited for, a
     * phone slow to write froze the captions until the conversation was
     * written, or until each closed one had been tried in turn.
     */
    fun fixLiveLine(segmentID: UUID, text: String) {
        if (currentLines.any { it.id == segmentID }) {
            writeInTurn(ended = false, endedAt = null)?.let { whenWritten(it.second) }
            return
        }
        if (closed.isEmpty()) return
        writer.fixTextInBackground(closed.keys.reversed(), segmentID, text)
        whenWritten()
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
            saveInBackground()
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
     *
     * The star is the other state of what the line shows, and its save is
     * handed to the writer without waiting: the main thread draws the
     * captions, and on a phone slow to write they froze until the
     * conversation was written. A save the phone refuses waits for room
     * with the star, which stays on the line, as before.
     */
    fun toggleLiveStar(segmentID: UUID): Boolean {
        val starring = segmentID !in starred
        mark(segmentID, starring)
        if (currentLines.any { it.id == segmentID }) {
            writeInTurn(ended = false, endedAt = null)
        } else if (closed.isNotEmpty()) {
            writer.toggleStarInBackground(closed.keys.reversed(), segmentID, starring)
        }
        return starring
    }

    fun isStarred(segmentID: UUID): Boolean = segmentID in starred

    private fun mark(segmentID: UUID, starring: Boolean) {
        if (starring) starred += segmentID else starred -= segmentID
        starTurns[segmentID] = (starTurns[segmentID] ?: 0) + 1
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

    /**
     * Android may end the app next: what was said since the last autosave
     * is handed to the writer now. With the captions on screen it is not
     * waited for: waiting froze them on a phone slow to write. [outOfSight],
     * nothing on screen can freeze and Android ends such an app first, so
     * this returns only once it is written.
     */
    fun memoryShort(outOfSight: Boolean = false) {
        if (outOfSight) {
            save(ended = false)
            return
        }
        writeInTurn(ended = false, endedAt = null)?.let { whenWritten(it.second) }
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
