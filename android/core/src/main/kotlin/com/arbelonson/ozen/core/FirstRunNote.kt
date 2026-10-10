package com.arbelonson.ozen.core

enum class FirstRunNote {
    ModelDownload,
    SpeechPermission,
    HomeComputer,
    None,
}

val TranscriptionEngineKind.firstRunNote: FirstRunNote
    get() = when (this) {
        TranscriptionEngineKind.WhisperKit -> FirstRunNote.ModelDownload
        TranscriptionEngineKind.AppleSpeech -> FirstRunNote.SpeechPermission
        TranscriptionEngineKind.HomeServer -> FirstRunNote.HomeComputer
        TranscriptionEngineKind.Cloud -> FirstRunNote.None
    }

val AppSettings.audioLeavesPhone: Boolean
    get() = when (engine) {
        TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.Cloud -> true
        TranscriptionEngineKind.AppleSpeech -> allowServerFallbackForAppleSpeech
        TranscriptionEngineKind.WhisperKit -> false
    }
