package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
}
