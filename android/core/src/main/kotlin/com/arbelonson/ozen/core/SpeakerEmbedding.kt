package com.arbelonson.ozen.core

interface SpeakerEmbedding {
    fun embed(samples: FloatArray, sampleRate: Double): FloatArray?

    val recommendedSimilarityThreshold: Float get() = AppSettings.default.speakerSimilarityThreshold

    val embeddingLength: Int? get() = null
}
