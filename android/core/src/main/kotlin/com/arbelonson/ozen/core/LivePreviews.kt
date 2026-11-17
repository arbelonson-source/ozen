package com.arbelonson.ozen.core

data class PreviewDemand(
    val appInFront: Boolean = true,
    val lockScreenCaptions: Boolean = false,
    val alertWordsSet: Boolean = false,
)

object LivePreviews {
    fun wanted(engine: TranscriptionEngineKind, demand: PreviewDemand): Boolean =
        engine != TranscriptionEngineKind.WhisperKit ||
            demand.appInFront ||
            demand.lockScreenCaptions ||
            demand.alertWordsSet
}
