package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudOddTimesTest {
    private fun speechmatics(start: String, end: String): List<CloudStreamToken> {
        val frame = """{"message":"AddTranscript","results":[{"type":"word","start_time":$start,"end_time":$end,"alternatives":[{"content":"שלום"}]}]}"""
        return assertNotNull(SpeechmaticsSpeech.reply(frame, "he") as? CloudStreamReply.Tokens).tokens
    }

    private fun assembly(start: String, end: String): List<CloudStreamToken> {
        val frame = """{"type":"Turn","end_of_turn":true,"turn_is_formatted":true,"transcript":"שלום","words":[{"text":"שלום","start":$start,"end":$end}]}"""
        return assertNotNull(AssemblyAISpeech.reply(frame, "he") as? CloudStreamReply.Tokens).tokens
    }

    @Test
    fun `Speechmatics keeps the words and drops a time that is huge, negative or beyond a day`() {
        for ((start, end) in listOf("1e300" to "1e300", "-1e300" to "2", "1e19" to "1.5")) {
            val tokens = speechmatics(start, end)
            assertEquals(listOf(" שלום"), tokens.map { it.text })
            assertNull(tokens.first().startMs, "$start $end")
        }
        assertEquals(2_000, speechmatics("1.5", "2").first().endMs)
        assertNull(speechmatics("1.5", "1e300").first().endMs)
    }

    @Test
    fun `AssemblyAI keeps the line and drops a word time that is huge or negative`() {
        for ((start, end) in listOf("1e300" to "1e300", "-1e300" to "200", "1e19" to "400")) {
            val tokens = assembly(start, end)
            assertEquals(" שלום", tokens.first().text)
            assertNull(tokens.first().startMs, "$start $end")
        }
        assertEquals(200, assembly("100", "200").first().endMs)
        assertNull(assembly("100", "1e300").first().endMs)
    }

    @Test
    fun `Soniox times beyond a day are dropped and the words stay`() {
        val frame = """{"tokens":[{"text":"שלום","is_final":true,"start_ms":-2147483648,"end_ms":2147483647}]}"""
        val tokens = assertNotNull(SonioxSpeech.reply(frame) as? CloudStreamReply.Tokens).tokens
        assertEquals("שלום", tokens.first().text)
        assertNull(tokens.first().startMs)
        assertNull(tokens.first().endMs)
    }

    @Test
    fun `a time too large for a number keeps the words, in both services`() {
        assertEquals(listOf(" שלום"), speechmatics("1e400", "2").map { it.text })
        assertNull(speechmatics("1e400", "2").first().startMs)
        assertNull(speechmatics("1.5", "-1e400").first().endMs)
        assertEquals(" שלום", assembly("1e400", "200").first().text)
        assertNull(assembly("1e400", "200").first().startMs)
    }

    @Test
    fun `a line whose first word has no time still splits once a later word gives it a start`() {
        val lines = CloudStreamLines(spaced = false)
        val first = CloudStreamToken(text = "א", isFinal = true)
        val second = CloudStreamToken(text = " ב", isFinal = true, startMs = 1_000, endMs = 2_000)
        val third = CloudStreamToken(text = " ג", isFinal = true, startMs = 3_000, endMs = 1_000 + CloudStreamLines.MAX_LINE_MS)
        val shown = lines.take(listOf(first, second, third), 1.0)
        assertTrue(shown.any { it.isFinal && it.text == "א ב" })
    }

    @Test
    fun `words whose end is before their start still let a long line split`() {
        val lines = CloudStreamLines(spaced = false)
        var closed = 0
        for (i in 0 until 30) {
            val word = CloudStreamToken(text = " ו", isFinal = true, startMs = i * 1_500, endMs = 0)
            closed += lines.take(listOf(word), 1.0).count { it.isFinal }
        }
        assertTrue(closed > 0)
    }
}
