package com.arbelonson.ozen.core

object CaptionProblemText {
    fun engineName(engine: TranscriptionEngineKind?): String = when (engine) {
        TranscriptionEngineKind.AppleSpeech -> tr("זיהוי הדיבור המובנה בטלפון", "The phone’s built-in recognition")
        TranscriptionEngineKind.Cloud -> tr("התמלול בענן", "Cloud transcription")
        TranscriptionEngineKind.HomeServer -> tr("המחשב בבית", "The home computer")
        TranscriptionEngineKind.WhisperKit, null -> tr("זיהוי הדיבור בטלפון", "The phone’s speech recognition")
    }

    fun microphoneNotResponding(inputName: String?): String =
        inputName?.let { tr("המיקרופון ⁨%1⁩ לא מגיב", "%1 isn’t responding", listOf(it)) }
            ?: tr("המיקרופון לא מגיב", "The microphone isn’t responding")

    fun transcriptionStopped(): String = tr("התמלול נעצר", "Transcription stopped")

    fun languageNotAvailable(engine: TranscriptionEngineKind?, language: UILanguage): String = tr(
        "%1 לא זמין בשפה %2 במכשיר הזה",
        "%1 isn’t available in %2 on this device",
        listOf(engineName(engine), language.spokenName),
    )

    fun modelDownloadFailed(): String = tr("הורדת המודל נכשלה", "Downloading the model failed")

    fun modelNotOnDevice(): String = tr("מודל הדיבור עוד לא הורד לטלפון", "The language model isn’t on this phone yet")

    fun modelLoadFailed(): String = tr("טעינת המודל נכשלה", "Loading the model failed")

    fun notEnoughMemory(): String = tr("אין בטלפון הזה מספיק זיכרון למודל הזה", "This phone doesn’t have enough memory for this model")

    fun temporarilyUnavailable(engine: TranscriptionEngineKind?): String =
        tr("%1 לא זמין כרגע", "%1 isn’t available right now", listOf(engineName(engine)))

    fun unavailable(engine: TranscriptionEngineKind?): String =
        tr("%1 לא זמין", "%1 isn’t available", listOf(engineName(engine)))

    fun startRefused(): String = tr(
        "הטלפון לא איפשר לאוזן להאזין. לחצו על התחלת כתוביות כדי לנסות שוב.",
        "The phone wouldn’t let Ozen listen. Tap Start captions to try again.",
    )
}
