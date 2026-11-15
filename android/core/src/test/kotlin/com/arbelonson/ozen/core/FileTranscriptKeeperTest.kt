package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTranscriptKeeperTest {
    private val folder = Files.createTempDirectory("ozen-keeper").toFile()
    private val history = File(folder, "history")
    private val marker = File(folder, "in-progress.txt")
    private var clock = 10_000.0
    private var saving = true
    private val store = TranscriptHistoryStore(history)

    private fun keeper(): FileTranscriptKeeper =
        FileTranscriptKeeper(store, marker, historyOn = { saving }, now = { clock }).also {
            it.begin("Lecture.mp3", 3_600.0, "small", "en")
        }

    private fun said(count: Int) = (1..count).map { TranscribedLine(it * 10.0, it * 10.0 + 4, "line $it") }

    private fun saved() = store.listSummaries()

    @Test
    fun linesAreSavedInBatchesWhileTheFileRuns() {
        val keeper = keeper()
        keeper.linesSoFar(said(4))
        assertTrue(saved().isEmpty(), "four lines is not yet a batch")
        keeper.linesSoFar(said(5))
        val record = store.load(keeper.id)!!
        assertEquals((1..5).map { "line $it" }, record.segments.map { it.text })
        assertNull(record.endedAt, "the file is still being worked on")
        keeper.linesSoFar(said(7))
        assertEquals(5, store.load(keeper.id)!!.segments.size)
        clock += 31
        keeper.linesSoFar(said(7))
        assertEquals(7, store.load(keeper.id)!!.segments.size)
        assertEquals(1, saved().size)
    }

    @Test
    fun anInterruptedRunBecomesACutShortConversationWithItsLinesNextLaunch() {
        val first = keeper()
        first.linesSoFar(said(6))
        val id = first.id
        val second = FileTranscriptKeeper(store, marker, historyOn = { saving }, now = { clock })
        val back = assertNotNull(second.recoverInterrupted())
        assertEquals(id, back.id)
        assertEquals("Lecture", back.name)
        val record = store.load(id)!!
        assertTrue(record.cutShort)
        assertNotNull(record.endedAt)
        assertEquals((1..6).map { "line $it" }, record.segments.map { it.text })
        assertEquals(1, saved().size)
        assertNull(second.recoverInterrupted(), "told only once")
        assertFalse(marker.exists())
    }

    @Test
    fun aNormalFinishLeavesExactlyOneConversationAndNothingToRecover() {
        val keeper = keeper()
        keeper.linesSoFar(said(5))
        val full = FileTranscriptRecord.make("Lecture.mp3", said(8), clock + 60, 3_600.0, "small", id = keeper.id, languageCode = "en")
        assertTrue(store.save(full))
        keeper.finished()
        assertEquals(listOf(keeper.id), saved().map { it.id })
        assertEquals(8, store.load(keeper.id)!!.segments.size)
        assertFalse(store.load(keeper.id)!!.cutShort)
        assertNull(FileTranscriptKeeper(store, marker, historyOn = { saving }).recoverInterrupted())
        assertEquals(1, saved().size)
    }

    @Test
    fun withHistoryOffNothingIsWrittenAndNothingIsSaidLater() {
        saving = false
        val keeper = keeper()
        keeper.linesSoFar(said(12))
        clock += 100
        keeper.linesSoFar(said(13))
        assertFalse(history.exists())
        assertFalse(marker.exists())
        assertNull(FileTranscriptKeeper(store, marker, historyOn = { saving }).recoverInterrupted())
    }

    @Test
    fun aStoppedRunLeavesNothingBehind() {
        val keeper = keeper()
        keeper.linesSoFar(said(9))
        keeper.discard()
        assertTrue(saved().isEmpty())
        assertFalse(marker.exists())
    }

    @Test
    fun aFileThatBreaksPartwayIsSavedOnceUnderTheSameIdWithItsLines() {
        val keeper = keeper()
        keeper.linesSoFar(said(6))
        val partial = FileTranscriptRecord.make("Lecture.mp3", said(6), clock + 90, 3_600.0, "small", id = keeper.id, languageCode = "en")
        assertTrue(store.save(partial))
        keeper.finished()
        assertEquals(listOf(keeper.id), saved().map { it.id })
        assertEquals(6, store.load(keeper.id)!!.segments.size)
        assertFalse(marker.exists())
        assertNull(FileTranscriptKeeper(store, marker, historyOn = { saving }).recoverInterrupted())
    }

    @Test
    fun stoppingBeforeAnyBatchWasSavedHasNothingToRemoveAndNothingToRecover() {
        val keeper = keeper()
        keeper.linesSoFar(said(2))
        keeper.discard()
        assertTrue(saved().isEmpty())
        assertNull(FileTranscriptKeeper(store, marker, historyOn = { saving }).recoverInterrupted())
    }

    @Test
    fun turningHistoryOffMidRunRemovesWhatWasAlreadyKept() {
        val keeper = keeper()
        keeper.linesSoFar(said(5))
        assertEquals(1, saved().size)
        saving = false
        keeper.linesSoFar(said(6))
        assertTrue(saved().isEmpty())
        assertFalse(marker.exists())
    }

    @Test
    fun finishingWithHistoryOffLeavesNoUnfinishedCopy() {
        val keeper = keeper()
        keeper.linesSoFar(said(5))
        saving = false
        keeper.finished()
        assertTrue(saved().isEmpty())
        assertFalse(marker.exists())
        assertNull(FileTranscriptKeeper(store, marker, historyOn = { saving }).recoverInterrupted())
    }
}
