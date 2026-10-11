package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechChunksTest {
    @Test
    fun `text the engine takes at once stays whole`() {
        assertEquals(listOf("Thank you for coming."), SpeechChunks.of("Thank you for coming.", 40))
    }

    @Test
    fun `longer text breaks after the last sentence that fits`() {
        val text = "Thank you for coming. Please speak slowly. I read everything on my phone."
        assertEquals(listOf("Thank you for coming. Please speak slowly.", "I read everything on my phone."), SpeechChunks.of(text, 50))
    }

    @Test
    fun `a sentence longer than the limit breaks between words`() {
        assertEquals(listOf("one two three", "four five six"), SpeechChunks.of("one two three four five six", 15))
    }

    @Test
    fun `text with no spaces breaks at the limit and keeps every character`() {
        val text = "a".repeat(25)
        val chunks = SpeechChunks.of(text, 10)
        assertEquals(listOf(10, 10, 5), chunks.map { it.length })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `a break never splits a character in two`() {
        val face = String(Character.toChars(0x1F600))
        val chunks = SpeechChunks.of("a".repeat(9) + face + "b", 10)
        assertTrue(chunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
        assertEquals("a".repeat(9) + face + "b", chunks.joinToString(""))
    }

    @Test
    fun `every piece fits and no words are lost`() {
        val text = "Please read this sentence aloud slowly. ".repeat(120).trim()
        val chunks = SpeechChunks.of(text, 4_000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 4_000 })
        assertEquals(text.split(" "), chunks.joinToString(" ").split(" "))
    }
}
