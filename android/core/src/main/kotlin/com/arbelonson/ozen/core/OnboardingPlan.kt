package com.arbelonson.ozen.core

enum class OnboardingPage { Welcome, HowItWorks, Language, Model, Microphone, Name, Ready }

object OnboardingPlan {
    fun pages(engine: TranscriptionEngineKind): List<OnboardingPage> =
        OnboardingPage.entries.filter { it != OnboardingPage.Model || usesPhoneModel(engine) }

    fun runsPhoneTest(engine: TranscriptionEngineKind): Boolean = usesPhoneModel(engine)

    fun showsDownloadNote(engine: TranscriptionEngineKind, modelFile: String, installed: Set<String>): Boolean =
        engine.firstRunNote == FirstRunNote.ModelDownload && modelFile !in installed

    private fun usesPhoneModel(engine: TranscriptionEngineKind): Boolean = engine == TranscriptionEngineKind.WhisperKit
}
