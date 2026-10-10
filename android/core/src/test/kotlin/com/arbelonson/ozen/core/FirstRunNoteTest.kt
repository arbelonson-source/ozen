package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunNoteTest {
    @Test
    fun `only Apple's recognition warns about iOS asking for speech permission, a paired home computer says so instead`() {
        assertEquals(FirstRunNote.ModelDownload, TranscriptionEngineKind.WhisperKit.firstRunNote)
        assertEquals(FirstRunNote.SpeechPermission, TranscriptionEngineKind.AppleSpeech.firstRunNote)
        assertEquals(FirstRunNote.HomeComputer, TranscriptionEngineKind.HomeServer.firstRunNote)
        assertEquals(FirstRunNote.None, TranscriptionEngineKind.Cloud.firstRunNote)
    }
}

class AudioLeavesPhoneTest {
    @Test
    fun `the first page's nothing is sent to the internet holds only when the audio stays on the phone`() {
        val settings = AppSettings.default
        settings.engine = TranscriptionEngineKind.WhisperKit
        assertFalse(settings.audioLeavesPhone)
        settings.engine = TranscriptionEngineKind.AppleSpeech
        settings.allowServerFallbackForAppleSpeech = false
        assertFalse(settings.audioLeavesPhone)
        settings.allowServerFallbackForAppleSpeech = true
        assertTrue(settings.audioLeavesPhone)
        settings.engine = TranscriptionEngineKind.HomeServer
        assertTrue(settings.audioLeavesPhone)
        settings.engine = TranscriptionEngineKind.Cloud
        assertTrue(settings.audioLeavesPhone)
    }
}
