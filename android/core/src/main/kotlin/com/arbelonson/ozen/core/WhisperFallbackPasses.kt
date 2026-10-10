package com.arbelonson.ozen.core

interface WhisperSinglePass {
    val specialTokenBegin: Int

    fun tokenize(text: String): List<Int>

    suspend fun once(audio: FloatArray, options: WhisperPassOptions, temperature: Float): List<WhisperSegment>
}

class WhisperFallbackPasses(private val single: WhisperSinglePass) : WhisperPasses {
    override val specialTokenBegin: Int get() = single.specialTokenBegin

    override fun tokenize(text: String): List<Int> = single.tokenize(text)

    override suspend fun run(audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
        var segments = emptyList<WhisperSegment>()
        for (attempt in 0..options.temperatureFallbackCount) {
            segments = single.once(audio, options, options.temperature + attempt * TEMPERATURE_STEP)
            if (!needsFallback(segments, options)) break
        }
        return segments
    }

    internal fun needsFallback(segments: List<WhisperSegment>, options: WhisperPassOptions): Boolean {
        val tokens = segments.flatMap { it.tokens }
        val first = tokens.firstOrNull() ?: return false
        if (WhisperPassScoring.logProbability(first) < options.firstTokenLogProbThreshold) return true
        if (segments.maxOf { it.noSpeechProbability } > options.noSpeechThreshold) return false
        val whole = WhisperSegment(text = "", noSpeechProbability = 0f, tokens = tokens)
        if (WhisperPassScoring.compressionRatio(whole, specialTokenBegin) > options.compressionRatioThreshold) return true
        return averageLogprob(tokens, options) < options.logProbThreshold
    }

    internal fun averageLogprob(tokens: List<WhisperToken>, options: WhisperPassOptions): Float {
        val sampled = tokens.filter { it.id != specialTokenBegin }
        val forced = if (options.withoutTimestamps) 4 else 3
        return sampled.sumOf { WhisperPassScoring.logProbability(it).toDouble() }.toFloat() / (forced + sampled.size + 1)
    }

    companion object {
        const val TEMPERATURE_STEP = 0.2f
    }
}
