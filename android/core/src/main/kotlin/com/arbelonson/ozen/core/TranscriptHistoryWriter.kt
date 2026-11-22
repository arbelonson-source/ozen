package com.arbelonson.ozen.core

import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * A serial queue: work runs one piece at a time on a thread of its own, in
 * the order it was handed in. [async] returns at once, [sync] waits for its
 * work. A suspended queue holds everything back until [resume].
 */
class SerialQueue(name: String = "ozen.serial-queue") {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).also { it.isDaemon = true }
    }
    private val gate = Object()
    private var suspendCount = 0

    fun suspend() {
        synchronized(gate) { suspendCount += 1 }
    }

    fun resume() {
        synchronized(gate) {
            if (suspendCount > 0) suspendCount -= 1
            gate.notifyAll()
        }
    }

    private fun waitWhileSuspended() {
        synchronized(gate) {
            while (suspendCount > 0) gate.wait()
        }
    }

    fun async(work: () -> Unit) {
        executor.execute {
            waitWhileSuspended()
            work()
        }
    }

    fun <T> sync(work: () -> T): T {
        val task = FutureTask(Callable {
            waitWhileSuspended()
            work()
        })
        executor.execute(task)
        try {
            return task.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}

/**
 * Writes conversations to history away from the caption screen's thread.
 *
 * The periodic autosave re-encodes the whole conversation, which for a
 * long afternoon with the family is enough work to stutter the captions
 * if it runs on the main thread every twenty seconds. Autosaves therefore
 * go to a serial queue and return at once.
 *
 * Saves that matter right now (listening stopped, the app is leaving the
 * screen, a conversation break) wait instead: they queue behind any
 * autosave still in flight, so an older autosave can never land on top
 * of the final version of a conversation.
 *
 * An autosave identical to what this writer last wrote is skipped: in a
 * quiet room the conversation doesn't change for hours, and writing the
 * same file again every twenty seconds only spends battery.
 */
class TranscriptHistoryWriter(
    private val store: TranscriptHistoryStore,
    private val queue: SerialQueue = SerialQueue("ozen.history-writer"),
) {
    private val failure = FailureBox()
    private val lastWritten = WrittenRecord()
    private val pendingRenames = PendingRenames()
    private val pendingSaves = PendingSaves()

    /**
     * Why the most recent save or rename didn't reach the disk (a full
     * phone, most likely), or null when it did. Autosaves have no one to
     * report to when they fail; without this a conversation could stop
     * being saved with nothing on screen saying so.
     */
    val lastFailure: String? get() = failure.value

    /**
     * Queues a save and returns immediately. [finished] runs on the
     * writer's queue once the save is done, with [lastFailure] already
     * saying how it went.
     */
    fun saveInBackground(record: TranscriptSessionRecord, finished: (() -> Unit)? = null) {
        queue.async {
            var saved = true
            if (lastWritten.record != record) {
                saved = failure.capture { store.save(record, throttleCaches = record.endedAt == null) }
                lastWritten.record = if (saved) record else null
                if (saved) pendingSaves.records.remove(record.id) else pendingSaves.records[record.id] = record
                pendingRenames.dropTexts(record)
            }
            // Unchanged, this one wasn't written again, so nothing cleared
            // the error of a waiting one that has now gone through.
            if (catchUp(record.id) && saved) {
                failure.clear()
            }
            finished?.invoke()
        }
    }

    /**
     * Waits for earlier queued saves, then writes this one before
     * returning. Called directly from the main thread when a conversation
     * stops or the app leaves the screen, so what it blocks on matters:
     * the summary and search-text caches are skipped here (see
     * `TranscriptHistoryStore.save`) so this holds the caller up only for
     * the one write that must not be lost, not for the whole
     * conversation's search index too.
     */
    fun saveNow(record: TranscriptSessionRecord): Boolean {
        var saved = false
        queue.sync {
            saved = failure.capture { store.save(record, updateSearchCaches = false) }
            lastWritten.record = if (saved) record else null
            if (saved) pendingSaves.records.remove(record.id) else pendingSaves.records[record.id] = record
            pendingRenames.dropTexts(record)
            catchUp(record.id)
        }
        return saved
    }

    /**
     * Names a conversation in the same queue as the saves, so an autosave
     * that already read the old summary can't land after the new name
     * and drop it.
     *
     * A version still waiting for room takes the name too: never written
     * before, it had no file for the name to go into, and it was written
     * later without one. False when the name was lost: the disk refused it
     * and no waiting version keeps it, so the screen must say so.
     */
    fun renameNow(id: UUID, title: String): Boolean = queue.sync {
        lastWritten.record = null
        var kept = failure.capture { store.rename(id, title) }
        val waiting = pendingSaves.records[id]
        if (waiting != null) {
            val trimmed = historyTrimmed(title)
            pendingSaves.records[id] = waiting.copy(title = trimmed.ifEmpty { null })
            kept = true
        }
        catchUp(null)
        kept
    }

    /**
     * A saved conversation as it will be saved, in order with the saves
     * already queued: its newer version still waiting for room (a phone too
     * full), with the name and source its save keeps from the file (see
     * [TranscriptHistoryStore.keepingSavedDetails]), or else its file, with
     * the line changes still waiting put in.
     * History shows this: read from the file alone, a word fixed while the
     * phone was full kept its old words there though the fix was called
     * done, and the person fixed the same word again.
     *
     * Throws when there is no waiting version and the file is there but
     * can't be read just now (see [TranscriptHistoryStore.loadThrowing]);
     * null when there is no such conversation, or its file is damaged for
     * good. Read as gone, the conversation open in History turned into
     * "Conversation not found" after a star or a name it refused there, as
     * on the iPhone.
     */
    @Throws(IOException::class)
    fun loadNow(id: UUID): TranscriptSessionRecord? = queue.sync {
        val waiting = pendingSaves.records[id]?.let {
            try {
                store.keepingSavedDetails(it)
            } catch (_: Exception) {
                it
            }
        }
        val record = waiting ?: store.loadThrowing(id) ?: return@sync null
        val texts = pendingRenames.texts.filter { it.sessionID == id }
        record.copy(segments = record.segments.map { line -> texts.lastOrNull { it.segmentID == line.id }?.let { line.copy(text = it.text) } ?: line })
    }

    /**
     * Renames a voice across every saved conversation, queued behind the
     * saves already waiting so none of them lands the old name back.
     */
    fun renameSpeakerInBackground(oldName: String, newName: String, finished: (() -> Unit)? = null) {
        queue.async {
            lastWritten.record = null
            pendingSaves.renameSpeaker(oldName, newName)
            pendingRenames.list.add(PendingRename(oldName, newName))
            failure.capture { runRenames() }
            if (catchUp(null)) {
                failure.clear()
            }
            finished?.invoke()
        }
    }

    /**
     * A rename the disk refused part way (a full phone) left the rest of
     * the saved conversations with the old name, and the next save that
     * worked cleared the error. It stays waiting instead, is tried again
     * after every save until it goes through, and until then its error
     * is the one reported.
     */
    private fun retryRenames() {
        if (pendingRenames.list.isNotEmpty()) {
            try {
                runRenames()
            } catch (error: Exception) {
                failure.record(error.toString())
            }
        }
        for (next in pendingRenames.texts.toList()) {
            try {
                store.fixText(next.segmentID, next.sessionID, next.text)
                pendingRenames.texts.remove(next)
            } catch (error: Exception) {
                failure.record(error.toString())
            }
        }
    }

    /**
     * A conversation the disk refused (a full phone) was never tried
     * again: the next save of another one worked and cleared the error,
     * and the warning with it, though the refused one was never written.
     * It stays waiting instead, is tried after every save of another
     * conversation, and until it is written its error is the one reported.
     */
    private fun retrySaves(current: UUID?) {
        for ((id, record) in pendingSaves.records.toList()) {
            if (id == current) continue
            try {
                store.save(record)
                pendingSaves.records.remove(id)
            } catch (error: Exception) {
                failure.record(error.toString())
            }
        }
    }

    /**
     * Tries again everything the disk refused before, after any write:
     * a star or a rename that worked used to clear the error, and the
     * warning with it, while a conversation was still waiting. Says
     * whether nothing is left waiting.
     */
    private fun catchUp(current: UUID?): Boolean {
        retrySaves(current)
        retryRenames()
        return pendingSaves.records.isEmpty() && pendingRenames.list.isEmpty() && pendingRenames.texts.isEmpty()
    }

    /** The waiting renames, oldest first; one refused stops the rest. */
    private fun runRenames() {
        while (pendingRenames.list.isNotEmpty()) {
            val next = pendingRenames.list.first()
            store.renameSpeaker(next.from, next.to)
            pendingRenames.list.removeAt(0)
        }
    }

    /**
     * Writes a hand-fixed line of a saved conversation. A write the disk
     * refused stays waiting, is tried again after every later write, and
     * until it goes through its error is the one reported.
     */
    fun fixTextNow(sessionID: UUID, segmentID: UUID, text: String): Boolean = queue.sync {
        lastWritten.record = null
        write(text, sessionID, segmentID)
    }

    /**
     * History's fix of a word, changing the line's words as they are when
     * the write happens. A text of that line still waiting for room has its
     * newest words: any later save of the conversation takes its place (see
     * [PendingRenames.dropTexts]). The fix changes that text, as on the
     * iPhone, and needs nothing from the file: worked out from the file's
     * words, a fix made while the file couldn't be read just now was
     * refused, though it could wait for room like the one before it.
     *
     * [WordFixResult.Saved] when it changed the line's newest words and they
     * are kept: in the file, in the waiting version, or waiting for room by
     * themselves, which History calls saved, as the iPhone does. Otherwise
     * nothing new is kept, and History says the fix was not saved; one kept
     * waiting all the same was written once there was room, and again by
     * the tap that tried once more. [WordFixResult.LineChanged] when the
     * line is gone or keeps its words, and when the version of its
     * conversation waiting for room no longer has the line (a replay took it
     * off the screen before the conversation closed): the fix was written to
     * the file and called saved, then that version was written over it
     * without the line. [WordFixResult.Unreadable] when no text of the line
     * waits and the file is there but its words couldn't be read just now:
     * History said the line changed or was gone, though the same fix went
     * through once the file could be read.
     *
     * A fix that wrote nothing (the file couldn't be read just now, or has
     * no such line) leaves [lastFailure] as it was, as on the iPhone. Kept
     * as the last save's error, that read made the caption screen say the
     * phone was probably full though nothing had failed to save, and a line
     * that was gone took away the warning of a save that really had failed.
     */
    fun fixTextNow(sessionID: UUID, segmentID: UUID, change: (String) -> String): WordFixResult = queue.sync {
        val waiting = pendingSaves.records[sessionID]
        val index = waiting?.segments?.indexOfFirst { it.id == segmentID } ?: -1
        if (waiting != null && index < 0) {
            if (catchUp(null)) failure.clear()
            return@sync WordFixResult.LineChanged
        }
        lastWritten.record = null
        val newest = pendingRenames.texts.lastOrNull { it.sessionID == sessionID && it.segmentID == segmentID }?.text
        if (newest != null) {
            val text = change(newest)
            if (text == newest) {
                catchUpAfterFix()
                return@sync WordFixResult.LineChanged
            }
            write(text, sessionID, segmentID)
            return@sync WordFixResult.Saved
        }
        var fixed: String? = null
        var changed: Boolean? = null
        val fix = { now: String -> change(now).also { fixed = it; changed = it != now } }
        var done = false
        val written = failure.capture(counts = { changed != null }) { done = store.fixText(segmentID, sessionID, fix) }
        if (waiting != null) {
            val segments = waiting.segments.toMutableList()
            segments[index] = segments[index].copy(text = fix(segments[index].text))
            pendingSaves.records[sessionID] = waiting.copy(segments = segments)
            done = true
        } else if (!written) {
            if (changed != true) {
                catchUpAfterFix()
                return@sync if (changed == null) WordFixResult.Unreadable else WordFixResult.LineChanged
            }
            fixed?.let { pendingRenames.texts.add(PendingText(sessionID, segmentID, it)) }
            done = true
        }
        catchUpAfterFix()
        if (done && changed == true) WordFixResult.Saved else WordFixResult.LineChanged
    }

    /**
     * A line's new words, in the file and in the version of its
     * conversation waiting for room when that has the line; refused with
     * neither, they wait for room by themselves. An older text of the line
     * still waiting goes either way: once there was room it was written
     * after this one and brought the old words back. Whether the line was
     * found, in the file or in the waiting version.
     */
    private fun write(text: String, sessionID: UUID, segmentID: UUID): Boolean {
        var found = false
        val written = failure.capture { found = store.fixText(segmentID, sessionID, text) }
        pendingRenames.texts.removeAll { it.sessionID == sessionID && it.segmentID == segmentID }
        val waiting = pendingSaves.records[sessionID]
        val index = waiting?.segments?.indexOfFirst { it.id == segmentID } ?: -1
        if (waiting != null && index >= 0) {
            val segments = waiting.segments.toMutableList()
            segments[index] = segments[index].copy(text = text)
            pendingSaves.records[sessionID] = waiting.copy(segments = segments)
            found = true
        } else if (!written) {
            pendingRenames.texts.add(PendingText(sessionID, segmentID, text))
        }
        catchUpAfterFix()
        return found
    }

    /**
     * [catchUp] after a fix. When something waited for room and all of it
     * is written now, there is room again, and the error of the write that
     * left it waiting is gone: kept, the caption screen still said the phone
     * was probably full until the next save. With nothing waiting, an
     * earlier error stays as it was.
     */
    private fun catchUpAfterFix() {
        val waited = pendingSaves.records.isNotEmpty() || pendingRenames.list.isNotEmpty() || pendingRenames.texts.isNotEmpty()
        if (catchUp(null) && waited) failure.clear()
    }

    /**
     * Stars or unstars a line of a saved conversation, in order with the
     * saves already queued. Null when the line wasn't found or couldn't be
     * saved.
     *
     * A newer version of the conversation still waiting for room takes the
     * same state: starring only the older file on disk was undone when the
     * waiting one was written over it. The tap follows the conversation as
     * History shows it (see [loadNow]): the waiting version when it has the
     * line, and the file then takes the waiting version's new state. Toggled
     * on its own, a file without the waiting version's star got the star
     * back when History offered to take it off. A file the disk still
     * refuses gives the waiting version the star when
     * [keepingRefused] (the caption screen, whose star shows from memory and
     * is saved once there is room). History passes false: the person is told
     * the star didn't change, so it mustn't turn up once there is room, or
     * be undone by the tap that tries again. With [to], the line takes that
     * state instead: the starred lines list shows the files' stars, and its
     * Remove the star, followed as a tap, put the star back on a line the
     * waiting version had none on. A line only the waiting version has is
     * not in the file, which then had nothing to refuse: History's star was
     * called saved while it only waited. Without [keepingRefused] the
     * waiting version is written first, and the star is refused while that
     * version still can't be.
     */
    fun toggleStarNow(sessionID: UUID, segmentID: UUID, keepingRefused: Boolean = true, to: Boolean? = null): Boolean? = queue.sync {
        lastWritten.record = null
        val starring = to ?: pendingSaves.records[sessionID]?.segments?.firstOrNull { it.id == segmentID }?.let { !it.isStarred }
        var result: Boolean? = null
        var reached = failure.capture { result = store.toggleStar(segmentID, sessionID, starring) }
        if (reached && result == null && !keepingRefused && pendingSaves.records[sessionID]?.segments?.any { it.id == segmentID } == true) {
            catchUp(null)
            if (pendingSaves.records.containsKey(sessionID)) return@sync null
            reached = failure.capture { result = store.toggleStar(segmentID, sessionID, starring) }
        }
        val waiting = pendingSaves.records[sessionID]
        if ((reached || keepingRefused) && waiting != null) {
            val index = waiting.segments.indexOfFirst { it.id == segmentID }
            if (index >= 0) {
                val line = waiting.segments[index]
                val starred = result ?: to ?: !line.isStarred
                val segments = waiting.segments.toMutableList()
                segments[index] = line.copy(isStarred = starred)
                pendingSaves.records[sessionID] = waiting.copy(segments = segments)
                result = starred
            }
        }
        catchUp(null)
        result
    }

    /**
     * Deletes a conversation after any autosave of it already queued, so
     * that autosave can't write it back a moment after it was deleted.
     */
    fun deleteNow(id: UUID) {
        queue.sync {
            lastWritten.record = null
            pendingSaves.records.remove(id)
            pendingRenames.texts.removeAll { it.sessionID == id }
            store.delete(id)
            // Deleting the one conversation that could not be written left
            // the warning up until the next save, while captions were stopped
            // for good.
            if (pendingSaves.records.isEmpty() && pendingRenames.list.isEmpty() && pendingRenames.texts.isEmpty()) {
                failure.clear()
            }
        }
    }

    /** Deletes every conversation, after the saves already queued. */
    fun deleteAllNow() {
        queue.sync {
            lastWritten.record = null
            pendingSaves.records.clear()
            store.deleteAll()
            pendingRenames.list.clear()
            pendingRenames.texts.clear()
            failure.clear()
        }
    }

    /**
     * Deletes conversations that have outlived the retention setting,
     * after the saves already queued. Returns how many were deleted.
     */
    fun deleteExpiredNow(retention: HistoryRetention, now: Double, protecting: Set<UUID>): Int {
        val cutoff = retention.cutoff(now) ?: return 0
        return queue.sync {
            lastWritten.record = null
            store.deleteConversations(cutoff, protecting)
        }
    }

    /** Returns once every queued save has been written. */
    fun waitUntilIdle() {
        queue.sync {}
    }

    private class FailureBox {
        private val lock = Any()
        private var stored: String? = null

        val value: String? get() = synchronized(lock) { stored }

        fun record(error: String) {
            synchronized(lock) { stored = error }
        }

        fun clear() {
            synchronized(lock) { stored = null }
        }

        /**
         * Runs [work], keeps its error (or that there was none), and returns
         * whether it succeeded. When [counts], asked once [work] is done,
         * says it only read, the error kept before stays as it was.
         */
        fun capture(counts: () -> Boolean = { true }, work: () -> Any?): Boolean {
            val outcome: String? = try {
                work()
                null
            } catch (error: Exception) {
                error.toString()
            }
            if (counts()) synchronized(lock) { stored = outcome }
            return outcome == null
        }
    }

    private class PendingRename(val from: String, val to: String)

    private class PendingText(val sessionID: UUID, val segmentID: UUID, val text: String)

    /** Voice renames and line-text fixes not yet written to every saved conversation. Only touched on the writer's queue. */
    private class PendingRenames {
        val list = ArrayList<PendingRename>()
        val texts = ArrayList<PendingText>()

        /**
         * A save of a conversation, written or left waiting, is newer than any
         * text of its lines still waiting: that text was written after it
         * once there was room, and History's next fix started from the older
         * words.
         */
        fun dropTexts(record: TranscriptSessionRecord) {
            val lines = record.segments.mapTo(HashSet()) { it.id }
            texts.removeAll { it.sessionID == record.id && it.segmentID in lines }
        }
    }

    /** Conversations the disk refused, waiting to be written. Only touched on the writer's queue. */
    private class PendingSaves {
        val records = LinkedHashMap<UUID, TranscriptSessionRecord>()

        /**
         * A voice renamed while they wait would otherwise come back under the
         * old name when they are finally written.
         */
        fun renameSpeaker(oldName: String, newName: String) {
            for ((id, record) in records.toList()) {
                records[id] = record.copy(
                    segments = record.segments.map { if (it.speakerName == oldName) it.copy(speakerName = newName) else it },
                )
            }
        }
    }

    /**
     * The conversation as the writer last wrote it; null after a failure or
     * anything else that changed history. Only touched on the writer's queue.
     */
    private class WrittenRecord {
        var record: TranscriptSessionRecord? = null
    }
}
