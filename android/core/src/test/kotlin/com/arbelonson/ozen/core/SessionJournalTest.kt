package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionJournalTest {
    private val temporary = TemporaryFolders()

    @AfterTest
    fun deleteTemporaryFolders() {
        temporary.deleteAll()
    }

    private fun temporaryFile(): File {
        val folder = temporary.make("ozen-journal-")
        return File(folder, "journal.log")
    }

    private fun spokenSegments() = listOf("תתקשרי לרופא", "מחר בעשר").map {
        TranscriptSegment(id = UUID.randomUUID(), text = it, isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 60.0)
    }

    private fun snapshotLines(segments: List<TranscriptSegment>) =
        ProblemSnapshot.lines(AppSettings.default, null, null, PipelineStats(), segments, "-", 0)

    @Test
    fun `what one run of the app wrote is there for the next one to read`() {
        val file = temporaryFile()
        val first = SessionJournal(file)
        first.append("listening", 1_800_000_000.0)
        first.append("microphone stopped delivering audio", 1_800_000_065.5)
        first.entries()

        val second = SessionJournal(file)
        second.append("app started", 1_800_000_100.0)

        assertEquals(listOf("listening", "microphone stopped delivering audio", "app started"), second.entries().map { it.text })
        assertEquals(1_800_000_065.5, second.entries()[1].at)
        assertEquals(
            "1800000000.00\tlistening\n1800000065.50\tmicrophone stopped delivering audio\n1800000100.00\tapp started\n",
            file.readText(),
        )
    }

    @Test
    fun `a line cut off when the app was killed mid-write doesn't swallow the next run's first line`() {
        val file = temporaryFile()
        file.writeText("1800000000.00\tlistening\n1800000001.00\tmicrophone sto")

        val journal = SessionJournal(file)
        journal.append("app started", 1_800_000_100.0)

        assertEquals(listOf("listening", "microphone sto", "app started"), journal.entries().map { it.text })
        assertEquals(1_800_000_100.0, journal.entries().last().at)
    }

    @Test
    fun `a caption ending in a carriage return doesn't swallow the line after it`() {
        val journal = SessionJournal(temporaryFile())
        journal.append("  line (final, sure -, 10:00:00): hello\r", 1_800_000_000.0)
        journal.append("  sound saved: problem-x.wav", 1_800_000_001.0)
        assertEquals(
            listOf("  line (final, sure -, 10:00:00): hello ", "  sound saved: problem-x.wav"),
            journal.entries().map { it.text },
        )
    }

    @Test
    fun `the caption lines a marked problem kept can be taken out, and everything else stays`() {
        val file = temporaryFile()
        val journal = SessionJournal(file)
        journal.append("listening", 1_800_000_000.0)
        for (line in snapshotLines(spokenSegments())) journal.append(line, 1_800_000_010.0)
        journal.append("  sound saved: problem.wav", 1_800_000_010.0)
        journal.append("stopped", 1_800_000_020.0)

        journal.removeEntries(ProblemSnapshot::isCaptionLine)

        val kept = SessionJournal(file).entries().map { it.text }
        assertFalse(kept.any { it.contains("תתקשרי לרופא") || it.contains("מחר בעשר") })
        assertEquals(6, kept.size)
        assertEquals("listening", kept.first())
        assertEquals("stopped", kept.last())
        assertTrue(kept.any { it.startsWith("PROBLEM MARKED") })
    }

    @Test
    fun `caption lines a full phone is still holding back are taken out too, and never reach the file once it has room`() {
        val file = temporaryFile()
        val folder = file.parentFile
        folder.setWritable(false)
        try {
            val journal = SessionJournal(file)
            journal.append("listening", 1_800_000_000.0)
            for (line in snapshotLines(spokenSegments())) journal.append(line, 1_800_000_010.0)
            journal.append("stopped", 1_800_000_020.0)
            assertTrue(journal.entries().any { it.text.contains("תתקשרי לרופא") })

            journal.removeEntries(ProblemSnapshot::isCaptionLine)
            folder.setWritable(true)
            journal.append("listening again", 1_800_000_030.0)
            journal.entries()

            val kept = SessionJournal(file).entries().map { it.text }
            assertFalse(kept.any { it.contains("תתקשרי לרופא") || it.contains("מחר בעשר") })
            assertEquals("listening", kept.first())
            assertEquals(listOf("stopped", "listening again"), kept.takeLast(2))
            assertTrue(kept.any { it.startsWith("PROBLEM MARKED") })
            assertFalse(file.readText().contains("תתקשרי לרופא"))
        } finally {
            folder.setWritable(true)
        }
    }

    @Test
    fun `lines from before a clock change keep the clock they were written at`() {
        val journal = SessionJournal(temporaryFile())
        val change = 1_800_000_000.0
        journal.append("listening", change - 3_600)
        journal.append("stopped", change + 3_600)
        val lines = journal.reportLines { if (it < change) 3 * 3_600 else 2 * 3_600 }
        assertEquals(listOf("2027-01-15 10:00:00 listening", "2027-01-15 11:00:00 stopped"), lines)
    }

    @Test
    fun `lines carry the day as well as the time, at her clock`() {
        val journal = SessionJournal(temporaryFile())
        journal.append("listening", 1_800_000_000.0)
        assertEquals(listOf("2027-01-15 11:00:00 listening"), journal.reportLines(3 * 3_600))
    }

    @Test
    fun `an error that runs to paragraphs stays one line, and a short one`() {
        val journal = SessionJournal(temporaryFile())
        journal.append("first\nsecond\tthird " + "x".repeat(2_000), 1.0)
        val entries = journal.entries()
        assertEquals(1, entries.size)
        assertTrue(entries[0].text.startsWith("first second third x"))
        assertTrue(entries[0].text.length <= SessionJournal.textLimit + 1)
        assertEquals(401, entries[0].text.length)
    }

    @Test
    fun `months of lines never grow the file without end - the oldest go`() {
        val file = temporaryFile()
        val journal = SessionJournal(file)
        val text = "y".repeat(300)
        for (index in 0 until 1_000) journal.append("$index $text", index.toDouble())
        val entries = journal.entries()
        assertTrue(entries.last().text.startsWith("999 "))
        assertTrue(entries.size >= 100)
        assertTrue(file.length() <= SessionJournal.maximumBytes + 1_000)
    }

    @Test
    fun `lines that could not be written, say on a full phone, are kept and written once the disk takes them`() {
        val file = temporaryFile()
        val folder = file.parentFile
        folder.setWritable(false)
        try {
            val journal = SessionJournal(file)
            journal.append("problem marked", 1_800_000_000.0)
            journal.entries()
            folder.setWritable(true)
            journal.append("listening", 1_800_000_010.0)
            assertEquals(listOf("problem marked", "listening"), journal.entries().map { it.text })
        } finally {
            folder.setWritable(true)
        }
    }

    @Test
    fun `lines the disk refused still show while they wait, so a report made meanwhile has the problem she marked`() {
        val file = temporaryFile()
        val folder = file.parentFile
        folder.setWritable(false)
        val journal = SessionJournal(file)
        try {
            journal.append("problem marked", 1_800_000_000.0)
            assertEquals(listOf("problem marked"), journal.entries().map { it.text })
        } finally {
            folder.setWritable(true)
            journal.entries()
        }
    }

    @Test
    fun `a file someone damaged loses the bad lines, not the rest`() {
        val file = temporaryFile()
        file.writeText("garbage\n12.00\tkept\n\tno time\n")
        assertEquals(listOf(SessionJournal.Entry(12.0, "kept")), SessionJournal(file).entries())
    }

    @Test
    fun `a burst of a thousand appends all still show up, in order, once asked for`() {
        val journal = SessionJournal(temporaryFile())
        for (index in 0 until 1_000) journal.append("line $index", index.toDouble())
        val entries = journal.entries()
        assertEquals(1_000, entries.size)
        assertEquals("line 0", entries.first().text)
        assertEquals("line 999", entries.last().text)
    }

    @Test
    fun `buffered lines reach the disk by themselves, batch after batch, with nobody reading them`() {
        val file = temporaryFile()
        val journal = SessionJournal(file)
        fun onDisk(text: String): Boolean {
            repeat(100) {
                if (file.exists() && file.readText().contains(text)) return true
                Thread.sleep(100)
            }
            return false
        }
        journal.append("first batch", 1.0)
        assertTrue(onDisk("first batch"))
        journal.append("second batch", 2.0)
        assertTrue(onDisk("second batch"))
    }

    @Test
    fun `a fresh append sits buffered in memory rather than touching disk right away - entries flushes it and sees it immediately regardless`() {
        val file = temporaryFile()
        val journal = SessionJournal(file)
        journal.append("not on disk yet", 1.0)
        Thread.sleep(50)
        val onDiskAlready = if (file.exists()) file.readText() else ""
        assertFalse(onDiskAlready.contains("not on disk yet"), "a single fresh append should be buffered, not hit disk immediately")
        assertEquals(listOf("not on disk yet"), journal.entries().map { it.text }, "entries() must flush and see it right away regardless of the buffering delay")
        assertNotNull(file.takeIf { it.exists() })
    }

    private fun markProblem(journal: SessionJournal, words: List<String>, at: Double) {
        val segments = words.map {
            TranscriptSegment(id = UUID.randomUUID(), text = it, isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 60.0)
        }
        snapshotLines(segments).forEach { journal.append(it, at) }
        journal.append("  sound saved: problem.wav", at)
    }

    @Test
    fun `the helper's lines carry the caption rows of the newest marked problem and none of an older one`() {
        val journal = SessionJournal(temporaryFile())
        journal.append("listening", 1_800_000_000.0)
        markProblem(journal, listOf("older words alpha", "older words beta"), 1_800_000_010.0)
        journal.append("microphone stopped delivering audio", 1_800_000_020.0)
        markProblem(journal, listOf("newer words gamma", "newer words delta", "newer words epsilon"), 1_800_000_030.0)

        val lines = journal.helperReportLines { 0 }

        assertEquals(3, lines.count { it.contains("  line (") })
        assertTrue(lines.any { it.contains("newer words gamma") })
        assertTrue(lines.any { it.contains("newer words epsilon") })
        assertFalse(lines.any { it.contains("older words") })
        assertEquals(2, lines.count { it.contains("PROBLEM MARKED") })
        assertTrue(lines.any { it.endsWith("microphone stopped delivering audio") })
        assertTrue(lines.any { it.endsWith("listening") })
    }

    @Test
    fun `with one marked problem the helper's lines are the journal's lines`() {
        val journal = SessionJournal(temporaryFile())
        journal.append("listening", 1_800_000_000.0)
        markProblem(journal, listOf("only words one", "only words two"), 1_800_000_010.0)
        journal.append("stopped", 1_800_000_020.0)

        assertEquals(journal.reportLines { 0 }, journal.helperReportLines { 0 })
    }

    @Test
    fun `caption rows left with no marked problem above them stay out of the helper's lines`() {
        val journal = SessionJournal(temporaryFile())
        journal.append("  line (final, sure -, 10:00:00): orphan words", 1_800_000_000.0)
        journal.append("listening", 1_800_000_010.0)

        assertEquals(1, journal.helperReportLines { 0 }.size)
        assertFalse(journal.helperReportLines { 0 }.joinToString().contains("orphan"))
    }

    private fun markedJournal(file: File = temporaryFile()): SessionJournal {
        val journal = SessionJournal(file)
        fun mark(words: String, at: Double) {
            val segments = listOf(TranscriptSegment(id = UUID.randomUUID(), text = words, isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 60.0))
            snapshotLines(segments).forEach { journal.append(it, at) }
        }
        journal.append("listening", 1_000.0)
        mark("words of the first", 1_100.0)
        mark("more of the first", 1_900.0)
        journal.append("stopped", 2_000.0)
        journal.append("listening", 3_000.0)
        mark("words of the second", 3_100.0)
        journal.append("stopped", 4_000.0)
        return journal
    }

    private fun summary(start: Double, end: Double?, lastLineAt: Double? = null) =
        TranscriptSessionSummary(id = UUID.randomUUID(), startedAt = start, endedAt = end, segmentCount = 1, preview = "", engine = TranscriptionEngineKind.WhisperKit, lastLineAt = lastLineAt)

    @Test
    fun `deleting a conversation takes out the caption lines marked inside it, and only those`() {
        val journal = markedJournal()
        val before = journal.entries()

        journal.removeCaptionLines(summary(1_000.0, 2_000.0), running = false, nextStartedAt = 3_000.0)

        val kept = journal.entries()
        assertFalse(kept.any { it.text.contains("of the first") })
        assertEquals(1, kept.count { it.text.contains("of the second") })
        assertEquals(before.filter { !ProblemSnapshot.isCaptionLine(it.text) }, kept.filter { !ProblemSnapshot.isCaptionLine(it.text) })
        assertEquals(3, kept.count { it.text.startsWith("PROBLEM MARKED") })
        assertEquals(before.size - 2, kept.size)
    }

    @Test
    fun `deleting a conversation nothing was marked in changes nothing`() {
        val journal = markedJournal()
        val before = journal.entries()

        journal.removeCaptionLines(summary(5_000.0, 6_000.0), running = false)

        assertEquals(before, journal.entries())
    }

    @Test
    fun `a conversation with no end time runs up to the next one, and a running one has no end`() {
        val cutShort = markedJournal()
        cutShort.removeCaptionLines(summary(1_000.0, null, lastLineAt = 1_900.0), running = false, nextStartedAt = 3_000.0)
        assertFalse(cutShort.entries().any { it.text.contains("of the first") })
        assertTrue(cutShort.entries().any { it.text.contains("of the second") })

        val running = markedJournal()
        running.removeCaptionLines(summary(3_000.0, null), running = true)
        assertTrue(running.entries().any { it.text.contains("of the first") })
        assertFalse(running.entries().any { it.text.contains("of the second") })
    }

    @Test
    fun `a conversation that listening resumed in before its summary was saved again still takes the lines marked after its old end`() {
        val journal = markedJournal()
        journal.removeCaptionLines(summary(3_000.0, 3_050.0), running = true)
        assertFalse(journal.entries().any { it.text.contains("of the second") })
        assertTrue(journal.entries().any { it.text.contains("of the first") })
    }

    private fun mark(journal: SessionJournal, words: String, at: Double, conversation: UUID?, microphone: String? = null) {
        val segment = TranscriptSegment(id = UUID.randomUUID(), text = words, isCommitted = true, startTimestamp = 0.0, lastUpdateTimestamp = 60.0)
        val input = microphone?.let { AudioInputDescriptor(uid = "u", portName = it, portType = AudioPortType.BuiltInMic) }
        ProblemSnapshot.lines(AppSettings.default, null, input, PipelineStats(), listOf(segment), "-", 0, conversationId = conversation)
            .forEach { journal.append(it, at) }
    }

    @Test
    fun `a problem marked in the quiet after the last caption, before the conversation closed, goes with its conversation`() {
        val journal = SessionJournal(temporaryFile())
        val first = summary(1_000.0, 1_500.0)
        mark(journal, "said before the quiet", 1_900.0, first.id)
        mark(journal, "said in another", 1_950.0, UUID.randomUUID(), microphone = "mic conversation ${first.id}")

        journal.removeCaptionLines(first, running = false)

        assertFalse(journal.entries().any { it.text.contains("said before the quiet") })
        assertTrue(journal.entries().any { it.text.contains("said in another") })
    }

    @Test
    fun `an interrupted conversation's window stops where the next one starts, and a block naming the next one is never touched`() {
        val journal = SessionJournal(temporaryFile())
        val interrupted = summary(1_000.0, null, lastLineAt = 1_900.0)
        mark(journal, "old block of the interrupted", 1_920.0, null)
        mark(journal, "old block of the new", 1_960.0, null)
        mark(journal, "named block of the new", 1_940.0, UUID.randomUUID())

        journal.removeCaptionLines(interrupted, running = false, nextStartedAt = 1_950.0)

        val kept = journal.entries().joinToString("\n") { it.text }
        assertFalse(kept.contains("old block of the interrupted"))
        assertTrue(kept.contains("old block of the new"))
        assertTrue(kept.contains("named block of the new"))
    }

    @Test
    fun `the conversation named in a header is read back, and a header without one reads as none`() {
        val id = UUID.randomUUID()
        val named = ProblemSnapshot.lines(AppSettings.default, null, null, PipelineStats(), emptyList(), "-", 0, conversationId = id)[0]
        val plain = ProblemSnapshot.lines(AppSettings.default, null, null, PipelineStats(), emptyList(), "-", 0)[0]
        assertEquals(id, ProblemSnapshot.conversationId(named))
        assertEquals(null, ProblemSnapshot.conversationId(plain))
        assertTrue(named.startsWith("PROBLEM MARKED: engine "))
    }

    @Test
    fun `an older block marked in the quiet after the saved end goes too, while one at the hundredth the next conversation starts in stays`() {
        val journal = SessionJournal(temporaryFile())
        val first = summary(1_000.004, 1_500.0)
        mark(journal, "at the very start", 1_000.004, null)
        mark(journal, "in the quiet", 1_800.0, null)
        mark(journal, "in the next hundredth", 1_950.0049, null)
        mark(journal, "inside the next", 1_951.0, null)

        journal.removeCaptionLines(first, running = false, nextStartedAt = 1_950.004)

        val kept = journal.entries().joinToString("\n") { it.text }
        assertFalse(kept.contains("at the very start"))
        assertFalse(kept.contains("in the quiet"))
        assertTrue(kept.contains("in the next hundredth"))
        assertTrue(kept.contains("inside the next"))
    }

    @Test
    fun `an older block marked after the saved end of the newest conversation goes with it`() {
        val journal = SessionJournal(temporaryFile())
        mark(journal, "after the saved end", 1_800.0, null)

        journal.removeCaptionLines(summary(1_000.0, 1_500.0), running = false)

        assertFalse(journal.entries().any { it.text.contains("after the saved end") })
    }
}
