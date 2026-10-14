package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WhisperPassScoringTest {
    private fun segment(vararg pieces: Pair<String, Float>) =
        WhisperSegment("", 0f, pieces.mapIndexed { index, (text, probability) -> WhisperToken(100 + index, text, probability) })

    private fun doubtful(text: String, vararg pieces: Pair<String, Float>): List<String> =
        UncertainWords.spoken(text, WhisperPassScoring.uncertainWords(segment(*pieces), 50000, "en"))

    @Test
    fun `a doubtful hour split by its colon is still found`() {
        val found = doubtful(
            "at 10:30 please",
            " at" to 0.95f, " 10" to 0.1f, ":" to 0.9f, "30" to 0.1f, " please" to 0.95f,
        )
        assertEquals(listOf("10:30"), found)
    }

    @Test
    fun `a doubtful price split by its point is still found`() {
        assertEquals(listOf("3.5"), doubtful(
                "it costs 3.5 now",
                " it" to 0.95f, " costs" to 0.95f, " 3" to 0.1f, "." to 0.9f, "5" to 0.1f, " now" to 0.95f,
            ))
    }

    @Test
    fun `an apostrophe piece stays inside its word`() {
        val found = doubtful(
            "I don't",
            " I" to 0.95f, " do" to 0.1f, "n" to 0.1f, "'" to 0.1f, "t" to 0.1f,
        )
        assertEquals(listOf("don't"), found)
    }

    @Test
    fun `a confident comma does not hide a doubtful word`() {
        val found = doubtful("Hello, there", " Hello" to 0.1f, "," to 0.99f, " there" to 0.95f)
        assertEquals(listOf("Hello"), found)
    }
}
