package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartialFileRunTest {
    private val model = PhoneModelCatalog.recommended(UILanguage.English)
    private val lines = listOf(
        TranscribedLine(0.0, 2.0, "one"),
        TranscribedLine(2.0, 4.0, "two"),
        TranscribedLine(4.0, 6.0, "three"),
    )

    private fun run(made: List<TranscribedLine>, saved: MutableList<List<TranscribedLine>>, history: HistorySave) =
        PartialFileRun.result("talk.m4a", UILanguage.English, model, made, FileTranscriptionProblem.Unexpected) { kept ->
            saved.add(kept)
            history
        }

    @Test
    fun threeLinesThenAFailureShowAndKeepExactlyThoseThreeAsCutShort() {
        val saved = mutableListOf<List<TranscribedLine>>()
        val id = UUID.randomUUID()
        val shown = run(lines, saved, HistorySave.Saved(id))
        assertEquals(FileTranscription.Done("talk.m4a", UILanguage.English, model, lines, HistorySave.Saved(id), endMissing = true), shown)
        assertEquals(listOf(lines), saved)
    }

    @Test
    fun aFailureBeforeAnyLineStillShowsTheFailure() {
        val saved = mutableListOf<List<TranscribedLine>>()
        val shown = run(emptyList(), saved, HistorySave.Off)
        assertEquals(FileTranscription.Failed("talk.m4a", FileTranscriptionProblem.Unexpected), shown)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun withHistorySavingOffTheThreeLinesAreStillShown() {
        val shown = run(lines, mutableListOf(), HistorySave.Off)
        assertEquals(FileTranscription.Done("talk.m4a", UILanguage.English, model, lines, HistorySave.Off, endMissing = true), shown)
    }
}
