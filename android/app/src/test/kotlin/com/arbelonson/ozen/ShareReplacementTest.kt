package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.FileTranscriptionProblem
import com.arbelonson.ozen.core.HistorySave
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.TranscribedLine
import com.arbelonson.ozen.core.UILanguage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareReplacementTest {
    private val model = PhoneModelCatalog.all.first()
    private val lines = listOf(TranscribedLine(0.0, 1.0, "hello"))
    private fun done(history: HistorySave) = FileTranscription.Done("a.mp3", UILanguage.English, model, lines, history, false)
    private val ready = FileTranscription.Ready("a.mp3", UILanguage.English, null, emptyList(), model, null, false)
    private val failed = FileTranscription.Failed("a.mp3", FileTranscriptionProblem.Unexpected)

    @Test
    fun aFileSharedOverUnsavedFinishedTextIsHeldAndAsksFirst() {
        for (history in listOf(HistorySave.Off, HistorySave.Failed)) {
            val guard = ShareReplacement<String>()
            assertFalse(guard.arrive("new.mp3", done(history)))
            assertEquals("new.mp3", guard.waiting.value)
        }
    }

    @Test
    fun aFileSharedOverSavedTextOrAnyOtherPageOpensAtOnce() {
        val pages = listOf(done(HistorySave.Saved(UUID.randomUUID())), FileTranscription.Idle, ready, failed)
        for (page in pages) {
            val guard = ShareReplacement<String>()
            assertTrue(guard.arrive("new.mp3", page))
            assertNull(guard.waiting.value)
        }
    }

    @Test
    fun replacingHandsBackTheHeldFileOnceAndStopsAsking() {
        val guard = ShareReplacement<String>()
        guard.arrive("new.mp3", done(HistorySave.Off))
        assertEquals("new.mp3", guard.replace())
        assertNull(guard.waiting.value)
        assertNull(guard.replace())
    }

    @Test
    fun keepingDropsTheHeldFileAndTheTextStays() {
        val guard = ShareReplacement<String>()
        guard.arrive("new.mp3", done(HistorySave.Off))
        guard.keep()
        assertNull(guard.waiting.value)
        assertNull(guard.replace())
    }

    @Test
    fun aSecondShareWhileAskingReplacesTheHeldOne() {
        val guard = ShareReplacement<String>()
        guard.arrive("one.mp3", done(HistorySave.Off))
        assertFalse(guard.arrive("two.mp3", done(HistorySave.Off)))
        assertEquals("two.mp3", guard.replace())
    }

    @Test
    fun theQuestionGoesWhenTheTextIsNoLongerOnlyOnTheScreen() {
        val guard = ShareReplacement<String>()
        guard.arrive("new.mp3", done(HistorySave.Off))
        guard.follow(done(HistorySave.Off))
        assertEquals("new.mp3", guard.waiting.value)
        guard.follow(FileTranscription.Idle)
        assertNull(guard.waiting.value)
    }

    @Test
    fun aFileSharedWhileTheReplacingOneIsStillBeingReadOpensAtOnce() {
        val guard = ShareReplacement<String>()
        guard.arrive("one.mp3", done(HistorySave.Off))
        assertEquals("one.mp3", guard.replace())
        assertTrue(guard.arrive("two.mp3", done(HistorySave.Off)))
        assertNull(guard.waiting.value)
    }

    @Test
    fun onceTheReplacingFileShowsAnItsPageAskingWorksAgain() {
        val guard = ShareReplacement<String>()
        guard.arrive("one.mp3", done(HistorySave.Off))
        guard.replace()
        guard.follow(ready)
        guard.follow(done(HistorySave.Off))
        assertFalse(guard.arrive("two.mp3", done(HistorySave.Off)))
        assertEquals("two.mp3", guard.waiting.value)
    }
}
