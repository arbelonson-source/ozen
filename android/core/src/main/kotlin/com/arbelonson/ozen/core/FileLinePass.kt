package com.arbelonson.ozen.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class FileLinePass(
    private val passes: WhisperPasses,
    private val languageCode: String,
    vocabulary: List<String>,
    private val passDispatcher: CoroutineDispatcher,
    filter: WhisperResultFilter = WhisperResultFilter(),
    private val retryDelayMillis: Long = 250,
) {
    private val speechFilter = filter.forSpeech(languageCode)
    private val echo = PromptEchoDetector(vocabulary).takeUnless { it.isEmpty }
    private val prompt: List<Int>? = promptTokens(vocabulary)
    private val options = WhisperPassOptions.final(languageCode).copy(promptTokens = prompt)

    val promptTokenCount: Int get() = prompt?.size ?: 0

    val longestStretchSeconds: Double get() = WhisperKitDecodeRoom.longestLineSeconds(promptTokenCount, MAXIMUM_STRETCH_SECONDS)

    suspend fun text(stretch: FloatArray, voicedChunks: Int?): String {
        val heard = SpeechGain.normalized(stretch)
        val results = try {
            run(heard)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            delay(retryDelayMillis)
            run(heard)
        }
        val summaries = results.map {
            WhisperSegmentSummary(
                text = it.text,
                noSpeechProb = it.noSpeechProbability,
                avgLogprob = WhisperPassScoring.averageLogprob(it, passes.specialTokenBegin) ?: Float.NEGATIVE_INFINITY,
                compressionRatio = WhisperPassScoring.compressionRatio(it, passes.specialTokenBegin),
                uncertainWords = emptyList(),
                temperature = it.temperature ?: 0f,
            )
        }
        val text = speechFilter.acceptedText(summaries, echo)
        return if (speechFilter.isUnvoicedPhrase(text, voicedChunks)) "" else text
    }

    private suspend fun run(audio: FloatArray): List<WhisperSegment> {
        val pass = CoroutineScope(passDispatcher).async { passes.run(audio, options) }
        try {
            return pass.await()
        } catch (stopped: CancellationException) {
            pass.cancel()
            passes.abortRunningPass()
            withContext(NonCancellable) { pass.join() }
            throw stopped
        }
    }

    private fun promptTokens(terms: List<String>): List<Int>? {
        if (terms.isEmpty()) return null
        val begin = passes.specialTokenBegin
        val encode = { text: String -> passes.tokenize(text).filter { it < begin } }
        val text = VocabularyHints.whisperPrompt(terms, WhisperKitDecodeRoom.maxPromptTokens) { encode(it).size }
        if (text.isEmpty()) return null
        return encode(" $text").take(WhisperKitDecodeRoom.maxPromptTokens).ifEmpty { null }
    }

    companion object {
        const val MAXIMUM_STRETCH_SECONDS = 28.0
    }
}

