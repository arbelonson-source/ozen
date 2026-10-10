package com.arbelonson.ozen.core

import java.io.File
import java.io.RandomAccessFile
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What happened to the captions, kept on disk.
 *
 * `PipelineEventLog` lives in memory: when the system ends the app, or she
 * closes it after an evening that went badly, the account of that evening
 * is gone before anyone can read it. This keeps the same kind of lines (and
 * the slower facts around them: which model and microphone, how long the
 * model took to load, heat, the app leaving the screen) in a small file, so
 * the diagnostics report can still say what happened last time.
 *
 * Lines are held in a memory buffer and only actually written to disk in
 * batches ([flushDelay] apart, or sooner if [entries] is asked for them),
 * instead of opening, seeking and closing the file for every single line:
 * during captioning the journal is appended to constantly (a line per
 * token, per stat), and unbuffered disk I/O for each one would waste
 * battery and flash wear for no benefit anyone reads in real time.
 * [flushDelay] bounds what a crash could lose; [entries] always flushes
 * first, so it never misses a line that was already asked to be appended.
 */
class SessionJournal(private val file: File) {
    data class Entry(val at: Double, val text: String)

    fun append(text: String, at: Double) {
        val singleLine = text.replace("\n", " ").replace("\r", " ").replace("\t", " ")
        val clipped = if (characterCount(singleLine) > textLimit) clip(singleLine) + "…" else singleLine
        val line = "${formatted(at)}\t$clipped\n"
        synchronized(lock) {
            pendingLines.getOrPut(file) { mutableListOf() }.add(line)
            if (!flushScheduled.add(file)) return
        }
        flusher.schedule({
            synchronized(lock) {
                flushScheduled.remove(file)
                flush(file)
            }
        }, flushDelayMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * Everything kept, oldest first. Flushes anything buffered first, so
     * this always sees every line already asked to be appended; lines a
     * full phone refused are listed from where they wait, or the report
     * sent from Diagnostics left out the problem she had just marked.
     */
    fun entries(): List<Entry> = synchronized(lock) {
        flush(file)
        read(file) + parse(pendingLines[file].orEmpty().joinToString(""))
    }

    /**
     * Takes out every kept line [shouldRemove] picks, whether still
     * buffered or on disk; the rest stay, in order.
     */
    fun removeEntries(shouldRemove: (String) -> Boolean) {
        synchronized(lock) {
            flush(file)
            pendingLines[file]?.let { pending ->
                pendingLines[file] = pending.filter { line ->
                    val tab = line.indexOf('\t')
                    tab < 0 || !shouldRemove(line.substring(tab + 1).dropLast(1))
                }.toMutableList()
            }
            val entries = read(file)
            val kept = entries.filter { !shouldRemove(it.text) }
            if (kept.size >= entries.size) return
            val text = kept.joinToString("") { "${formatted(it.at)}\t${it.text}\n" }
            try {
                PrivateFileWrites.write(file, text.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
            }
        }
    }

    /** "2026-09-18 14:02:07 listening", oldest first. */
    fun reportLines(utcOffsetSeconds: Int): List<String> = reportLines { utcOffsetSeconds }

    /**
     * Each line at the offset its own moment had: the journal keeps days
     * of lines, and today's offset put those from before a daylight-saving
     * change an hour off.
     */
    fun reportLines(utcOffsetAt: (Double) -> Int): List<String> = entries().map { entry ->
        val seconds = utcOffsetAt(entry.at)
        "${formattedDay(entry.at, seconds)} ${TranscriptHistoryStore.formattedClockTime(entry.at, seconds)} ${entry.text}"
    }

    companion object {
        const val maximumBytes = 96_000
        const val keptLines = 500
        internal const val textLimit = 400
        internal const val flushDelayMillis = 2_000L

        // One lock for every journal, so two of them on one file (tests, or
        // a second one made by mistake) still write and read in order. It
        // also guards the pending-lines buffers below, which are keyed by
        // file rather than by instance for the same reason: a line appended
        // by one instance must be visible to `entries()` on another instance
        // opened on the same file right after (e.g. across a simulated
        // relaunch).
        private val lock = Any()
        private val pendingLines = HashMap<File, MutableList<String>>()
        private val flushScheduled = HashSet<File>()
        private val flusher by lazy {
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "ozen-session-journal").apply { isDaemon = true }
            }
        }

        // Writes every buffered line for [file] in one batch. Must only be
        // called while holding [lock].
        private fun flush(file: File) {
            val lines = pendingLines.remove(file)
            if (lines.isNullOrEmpty()) return
            // A full phone refuses the write; a problem she just marked would
            // go with it while the screen said it was saved. Kept for the
            // next flush instead, no more than the file itself would keep.
            if (!write(lines.joinToString(""), file)) {
                pendingLines[file] = (lines + pendingLines[file].orEmpty()).takeLast(keptLines).toMutableList()
            }
        }

        private fun write(text: String, file: File): Boolean {
            try {
                if (!file.exists()) {
                    file.absoluteFile.parentFile?.mkdirs()
                    file.createNewFile()
                }
                RandomAccessFile(file, "rw").use { handle ->
                    val end = handle.length()
                    var data = text.toByteArray(Charsets.UTF_8)
                    // A last line cut off by the app being killed or the disk
                    // filling mid-write has no newline: the next line was
                    // glued onto it, and the two read back as one garbled
                    // entry.
                    if (end > 0) {
                        handle.seek(end - 1)
                        if (handle.read() != '\n'.code) data = byteArrayOf('\n'.code.toByte()) + data
                    }
                    handle.seek(end)
                    handle.write(data)
                    // Checked against the size after this write, not before:
                    // a buffered flush can write many lines at once, and a
                    // batch alone can carry the file past the limit in a
                    // single call.
                    if (end + data.size <= maximumBytes) return true
                }
            } catch (_: Exception) {
                return false
            }
            // Down to half, not to just under the limit, or every line after
            // the first trim would rewrite the whole file.
            var budget = maximumBytes / 2
            val kept = mutableListOf<String>()
            for (entry in read(file).takeLast(keptLines).reversed()) {
                val line = "${formatted(entry.at)}\t${entry.text}\n"
                budget -= line.toByteArray(Charsets.UTF_8).size
                if (budget < 0) break
                kept.add(line)
            }
            try {
                PrivateFileWrites.write(file, kept.reversed().joinToString("").toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
            }
            return true
        }

        private fun formatted(time: Double): String = String.format(Locale.ROOT, "%.2f", time)

        private fun read(file: File): List<Entry> {
            val bytes = try {
                file.readBytes()
            } catch (_: Exception) {
                return emptyList()
            }
            return parse(String(bytes, Charsets.UTF_8))
        }

        private fun parse(text: String): List<Entry> = text.split("\n").mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) return@mapNotNull null
            val at = timeOrNull(line.substring(0, tab)) ?: return@mapNotNull null
            Entry(at, line.substring(tab + 1))
        }

        private fun timeOrNull(text: String): Double? {
            if (text.last() in "dDfF") return null
            return text.toDoubleOrNull()
        }

        private fun characterCount(text: String): Int {
            if (text.length <= textLimit) return text.length
            val boundaries = BreakIterator.getCharacterInstance()
            boundaries.setText(text)
            var count = 0
            while (boundaries.next() != BreakIterator.DONE) count++
            return count
        }

        private fun clip(text: String): String {
            val boundaries = BreakIterator.getCharacterInstance()
            boundaries.setText(text)
            var end = boundaries.first()
            repeat(textLimit) { end = boundaries.next() }
            return text.substring(0, end)
        }

        internal fun formattedDay(timestamp: Double, utcOffsetSeconds: Int): String {
            val date = CivilDate.fromDaysSinceEpoch(CivilDate.localDay(timestamp, utcOffsetSeconds))
            return String.format(Locale.ROOT, "%04d-%02d-%02d", date.year, date.month, date.day)
        }
    }
}
