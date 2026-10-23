package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTranscriptionTest {
    private fun problem(kind: EngineUnavailability.Kind) = FileTranscriptionProblem.of(EngineUnavailability(kind, "x"))

    @Test
    fun aFullPhoneIsToldSoRatherThanThatTheDownloadFailed() {
        assertEquals(FileTranscriptionProblem.NotEnoughStorage, problem(EngineUnavailability.Kind.NotEnoughStorage))
        assertEquals(FileTranscriptionProblem.NeedsWiFi, problem(EngineUnavailability.Kind.WaitingForWiFi))
        assertEquals(FileTranscriptionProblem.ModelFailed, problem(EngineUnavailability.Kind.ModelLoadFailed))
        assertEquals(FileTranscriptionProblem.ModelDownloadFailed, problem(EngineUnavailability.Kind.ModelDownloadFailed))
    }

    @Test
    fun everyProblemHasItsOwnPlainMessageInBothLanguages() {
        val english = Localization.withLanguage(UILanguage.English) { FileTranscriptionProblem.entries.map { it.message } }
        val hebrew = Localization.withLanguage(UILanguage.Hebrew) { FileTranscriptionProblem.entries.map { it.message } }
        assertEquals(english.size, english.toSet().size)
        assertEquals(hebrew.size, hebrew.toSet().size)
        for (index in english.indices) assertNotEquals(english[index], hebrew[index])
        assertTrue(english.none { it.contains("Exception") || it.contains("null") })
    }

    private val outcomes = listOf(HistorySave.Saved(UUID.randomUUID()), HistorySave.Off, HistorySave.Failed)

    @Test
    fun aSaveThePhoneRefusedIsToldApartFromSavingBeingOff() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("History saving is off, so this wasn’t saved to History.", HistorySave.Off.message)
            assertEquals("Couldn’t save to History. The phone may be full. Share the text before you close this screen.", HistorySave.Failed.message)
        }
    }

    @Test
    fun everyHistoryOutcomeHasItsOwnPlainMessageInBothLanguages() {
        val english = Localization.withLanguage(UILanguage.English) { outcomes.map { it.message } }
        val hebrew = Localization.withLanguage(UILanguage.Hebrew) { outcomes.map { it.message } }
        assertEquals(english.size, english.toSet().size)
        assertEquals(hebrew.size, hebrew.toSet().size)
        for (index in english.indices) assertNotEquals(english[index], hebrew[index])
        assertTrue(english.none { it.contains("Exception") || it.contains("null") })
    }

    @Test
    fun theRecordIdIsThereOnlyWhenHistoryHasTheRecording() {
        val model = FileModelChoice.toDownload(UILanguage.Hebrew)
        fun done(history: HistorySave) = FileTranscription.Done("note.wav", UILanguage.Hebrew, model, emptyList(), history)
        val id = UUID.randomUUID()
        assertEquals(id, done(HistorySave.Saved(id)).recordId)
        assertNull(done(HistorySave.Off).recordId)
        assertNull(done(HistorySave.Failed).recordId)
    }
}
