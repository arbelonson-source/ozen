package com.arbelonson.ozen

import android.speech.SpeechRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuiltInRecognitionTest {
    private val languages = OnDeviceLanguages(
        installed = listOf("en-US"),
        downloading = listOf("fr-FR"),
        canDownload = listOf("iw-IL", "de-DE"),
        online = listOf("ar-SA"),
    )

    @Test
    fun `Hebrew is found under either of its codes, and only Hebrew is`() {
        assertTrue(BuiltInRecognition.matches("iw-IL", "he"))
        assertTrue(BuiltInRecognition.matches("he-IL", "he"))
        assertTrue(BuiltInRecognition.matches("he_IL", "iw"))
        assertFalse(BuiltInRecognition.matches("hi-IN", "he"))
        assertFalse(BuiltInRecognition.matches("en-US", "he"))
    }

    @Test
    fun `each language says how far the phone has it, best first`() {
        assertEquals("on device he can download, en installed", BuiltInRecognition.describe(listOf("he", "en"), true, languages))
        assertEquals("on device fr downloading, ar online only, ru no", BuiltInRecognition.describe(listOf("fr", "ar", "ru"), true, languages))
        val both = languages.copy(installed = listOf("he-IL"))
        assertEquals("on device he installed", BuiltInRecognition.describe(listOf("he", "iw"), true, both))
    }

    @Test
    fun `without on-device recognition, or before Android 13 lists languages, it says so`() {
        assertEquals("on device none", BuiltInRecognition.describe(listOf("he"), false, languages))
        assertEquals("on device yes, languages unknown before Android 13", BuiltInRecognition.describe(listOf("he"), true, null))
    }

    @Test
    fun `a recognizer still shutting down from the last question is a race to ask again, not a refusal`() {
        assertEquals(BuiltInRecognition.SessionError.Race, BuiltInRecognition.sessionError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
        assertEquals(BuiltInRecognition.SessionError.Race, BuiltInRecognition.sessionError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }

    @Test
    fun `a session that heard nothing ends, and any other error refuses Ozen's audio`() {
        assertEquals(BuiltInRecognition.SessionError.HeardNothing, BuiltInRecognition.sessionError(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(BuiltInRecognition.SessionError.HeardNothing, BuiltInRecognition.sessionError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(BuiltInRecognition.SessionError.Refusal, BuiltInRecognition.sessionError(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(BuiltInRecognition.SessionError.Refusal, BuiltInRecognition.sessionError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(BuiltInRecognition.SessionError.Refusal, BuiltInRecognition.sessionError(SpeechRecognizer.ERROR_CLIENT))
    }
}
