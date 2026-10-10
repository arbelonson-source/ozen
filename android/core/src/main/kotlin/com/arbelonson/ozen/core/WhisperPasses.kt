package com.arbelonson.ozen.core

class WhisperToken(
    val id: Int,
    val piece: ByteArray,
    val probability: Float,
    val isSpecial: Boolean,
) {
    constructor(id: Int, text: String, probability: Float, isSpecial: Boolean = false) :
        this(id, text.toByteArray(Charsets.UTF_8), probability, isSpecial)
}

class WhisperSegment(
    val text: String,
    val noSpeechProbability: Float,
    val tokens: List<WhisperToken>,
    val temperature: Float? = null,
)

data class WhisperPassOptions(
    val language: String,
    val isFinal: Boolean,
    val promptTokens: List<Int>? = null,
    val maxTokens: Int? = null,
    val temperature: Float = 0f,
    val temperatureFallbackCount: Int = 0,
    val suppressBlank: Boolean = true,
    val withoutTimestamps: Boolean = true,
    val compressionRatioThreshold: Float = 2.4f,
    val logProbThreshold: Float = -1.0f,
    val firstTokenLogProbThreshold: Float = -1.5f,
    val noSpeechThreshold: Float = 0.6f,
) {
    companion object {
        fun live(language: String) = WhisperPassOptions(language = language, isFinal = false)

        fun final(language: String) =
            WhisperPassOptions(language = language, isFinal = true, temperatureFallbackCount = 2)
    }
}

interface WhisperPasses {
    val specialTokenBegin: Int

    fun tokenize(text: String): List<Int>

    suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment>
}

class WhisperLoadedModel(val passes: WhisperPasses, val isFirstTime: Boolean = false, val release: () -> Unit = {})

interface WhisperModelLoader {
    suspend fun load(
        languageCode: String,
        cellularDownloadAllowed: Boolean,
        progress: (EnginePreparationProgress) -> Unit,
    ): WhisperLoadedModel

    suspend fun pendingDownloadMegabytes(): Int? = null

    suspend fun pendingInstallMegabytes(): Int? = pendingDownloadMegabytes()

    suspend fun cancelDownload() {}
}
