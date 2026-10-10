package com.arbelonson.ozen.core

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class WhisperFallbackPassesTest {
    private val eot = 50_257

    private class ScriptedPass(override val specialTokenBegin: Int, private val answer: (Float) -> List<WhisperSegment>) : WhisperSinglePass {
        val temperatures = mutableListOf<Float>()

        override fun tokenize(text: String): List<Int> = emptyList()

        override suspend fun once(audio: FloatArray, options: WhisperPassOptions, temperature: Float): List<WhisperSegment> {
            temperatures.add(temperature)
            return answer(temperature)
        }
    }

    private fun words(count: Int, logprob: Double, noSpeech: Float = 0f, firstLogprob: Double = logprob, sameWord: Boolean = false) =
        listOf(
            WhisperSegment(
                text = "words",
                noSpeechProbability = noSpeech,
                tokens = List(count) { index ->
                    val chance = exp(if (index == 0) firstLogprob else logprob).toFloat()
                    WhisperToken(id = if (sameWord) 1_000 else (index * 7_919 + 1_234) % 50_000, text = " w$index", probability = chance)
                } + WhisperToken(id = eot, text = "", probability = 1f, isSpecial = true),
            ),
        )

    private fun temperatures(options: WhisperPassOptions, answer: (Float) -> List<WhisperSegment>): List<Float> {
        val pass = ScriptedPass(eot, answer)
        runBlocking { WhisperFallbackPasses(pass).run(FloatArray(16_000), options) }
        return pass.temperatures
    }

    private val final = WhisperPassOptions.final("he")

    @Test
    fun `a sure final pass runs once, at temperature 0`() {
        assertEquals(listOf(0f), temperatures(final) { words(8, logprob = -0.1) })
    }

    @Test
    fun `an unsure final pass tries 0, 0,2 and 0,4 and keeps the last`() {
        val pass = ScriptedPass(eot) { words(8, logprob = -3.0) }
        val kept = runBlocking { WhisperFallbackPasses(pass).run(FloatArray(16_000), final) }
        assertEquals(listOf(0f, 0.2f, 0.4f), pass.temperatures.map { Math.round(it * 10) / 10f })
        assertEquals(1, kept.size)
    }

    @Test
    fun `a live pass never retries, however unsure`() {
        assertEquals(listOf(0f), temperatures(WhisperPassOptions.live("he")) { words(8, logprob = -3.0) })
    }

    @Test
    fun `a retry that comes out sure stops the retries there`() {
        assertEquals(2, temperatures(final) { if (it == 0f) words(8, logprob = -3.0) else words(8, logprob = -0.1) }.size)
    }

    @Test
    fun `the average counts the four forced tokens and the end as sure, as WhisperKit's does`() {
        assertEquals(1, temperatures(final) { words(5, logprob = -2.4, firstLogprob = -0.1) }.size)
        assertEquals(3, temperatures(final) { words(6, logprob = -2.4, firstLogprob = -0.1) }.size)
    }

    @Test
    fun `an unsure first word retries even when the rest is sure`() {
        assertEquals(3, temperatures(final) { words(8, logprob = -0.1, firstLogprob = -1.6) }.size)
        assertEquals(1, temperatures(final) { words(8, logprob = -0.1, firstLogprob = -1.4) }.size)
    }

    @Test
    fun `an unsure first word retries even in what sounds like silence, which only spares a low average`() {
        assertEquals(3, temperatures(final) { words(8, logprob = -0.1, noSpeech = 0.9f, firstLogprob = -1.6) }.size)
        assertEquals(1, temperatures(final) { words(8, logprob = -3.0, noSpeech = 0.9f, firstLogprob = -0.1) }.size)
        assertEquals(3, temperatures(final) { words(8, logprob = -3.0, noSpeech = 0.5f, firstLogprob = -0.1) }.size)
    }

    @Test
    fun `the same word over and over retries however sure the model was`() {
        assertEquals(3, temperatures(final) { words(60, logprob = -0.05, sameWord = true) }.size)
        assertEquals(1, temperatures(final) { words(60, logprob = -0.05) }.size)
    }

    @Test
    fun `nothing heard is no reason to retry`() {
        assertEquals(1, temperatures(final) { emptyList() }.size)
    }
}
