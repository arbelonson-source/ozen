package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
