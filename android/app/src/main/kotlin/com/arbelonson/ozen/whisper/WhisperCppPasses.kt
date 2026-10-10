package com.arbelonson.ozen.whisper

import com.arbelonson.ozen.core.WhisperPassOptions
import com.arbelonson.ozen.core.WhisperSinglePass
import com.arbelonson.ozen.core.WhisperToken
import com.arbelonson.ozen.core.WhisperSegment as PassSegment

class WhisperCppPasses(private val model: WhisperModel, private val threads: Int) : WhisperSinglePass {
    private val pieces = HashMap<Int, ByteArray>()

    override val specialTokenBegin: Int = model.withHandle { WhisperCpp.tokenEot(it) }

    override fun tokenize(text: String): List<Int> =
        model.withHandle { WhisperCpp.tokenize(it, text) }?.toList() ?: emptyList()

    override suspend fun once(audio: FloatArray, options: WhisperPassOptions, temperature: Float): List<PassSegment> =
        model.withHandle { handle ->
            val count = WhisperCpp.pass(
                handle, audio, options.language, options.promptTokens?.toIntArray(), options.maxTokens ?: 0,
                temperature, threads, options.suppressBlank, options.withoutTimestamps,
            )
            check(count >= 0) { "whisper.cpp could not run the pass" }
            (0 until count).map { segment -> segment(handle, segment, temperature) }
        }

    private fun segment(handle: Long, segment: Int, temperature: Float): PassSegment {
        val ids = WhisperCpp.segmentTokenIds(handle, segment)
        val chances = WhisperCpp.segmentTokenProbabilities(handle, segment)
        return PassSegment(
            text = WhisperCpp.segmentText(handle, segment).toString(Charsets.UTF_8),
            noSpeechProbability = WhisperCpp.segmentNoSpeech(handle, segment),
            tokens = ids.indices.map { index ->
                val id = ids[index]
                WhisperToken(id, piece(handle, id), chances[index], id >= specialTokenBegin)
            },
            temperature = temperature,
        )
    }

    private fun piece(handle: Long, id: Int): ByteArray = pieces.getOrPut(id) { WhisperCpp.tokenPiece(handle, id) }
}
