package com.arbelonson.ozen.core

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
                saved = failure.capture { store.save(record) }
                lastWritten.record = if (saved) record else null
                if (saved) pendingSaves.records.remove(record.id) else pendingSaves.records[record.id] = record
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
    fun saveNow(record: TranscriptSessionRecord) {
        queue.sync {
            val saved = failure.capture { store.save(record, updateSearchCaches = false) }
            lastWritten.record = if (saved) record else null
            if (saved) pendingSaves.records.remove(record.id) else pendingSaves.records[record.id] = record
            catchUp(record.id)
        }
    }

    /**
     * Names a conversation in the same queue as the saves, so an autosave
     * that already read the old summary can't land after the new name
     * and drop it.
     *
     * A version still waiting for room takes the name too: never written
     * before, it had no file for the name to go into, and it was written
     * later without one.
     */
    fun renameNow(id: UUID, title: String) {
        queue.sync {
            lastWritten.record = null
            failure.capture { store.rename(id, title) }
            val waiting = pendingSaves.records[id]
            if (waiting != null) {
                val trimmed = historyTrimmed(title)
                pendingSaves.records[id] = waiting.copy(title = trimmed.ifEmpty { null })
            }
            catchUp(null)
        }
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
        if (pendingRenames.list.isEmpty()) return
        try {
            runRenames()
        } catch (error: Exception) {
            failure.record(error.toString())
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
        return pendingSaves.records.isEmpty() && pendingRenames.list.isEmpty()
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
     * Stars or unstars a line of a saved conversation, in order with the
     * saves already queued. Null when the line wasn't found or couldn't be
     * saved.
     *
     * A newer version of the conversation still waiting for room takes the
     * same state: starring only the older file on disk was undone when the
     * waiting one was written over it. The tap follows the file, which is
     * what History shows; a line only the waiting version has follows that.
     */
    fun toggleStarNow(sessionID: UUID, segmentID: UUID): Boolean? = queue.sync {
        lastWritten.record = null
        var result: Boolean? = null
        failure.capture { result = store.toggleStar(segmentID, sessionID) }
        val waiting = pendingSaves.records[sessionID]
        if (waiting != null) {
            val index = waiting.segments.indexOfFirst { it.id == segmentID }
            if (index >= 0) {
                val line = waiting.segments[index]
                val starred = result ?: !line.isStarred
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
            store.delete(id)
            // Deleting the one conversation that could not be written left
            // the warning up until the next save, while captions were stopped
            // for good.
            if (pendingSaves.records.isEmpty() && pendingRenames.list.isEmpty()) {
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

        /** Runs [work], keeps its error (or that there was none), and returns whether it succeeded. */
        fun capture(work: () -> Any?): Boolean {
            val outcome: String? = try {
                work()
                null
            } catch (error: Exception) {
                error.toString()
            }
            synchronized(lock) { stored = outcome }
            return outcome == null
        }
    }

    private class PendingRename(val from: String, val to: String)

    /** Voice renames not yet written to every saved conversation. Only touched on the writer's queue. */
    private class PendingRenames {
        val list = ArrayList<PendingRename>()
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
