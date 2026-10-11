package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechResumeTest {
    private fun line(text: String) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = false, startTimestamp = 0.0, lastUpdateTimestamp = 0.0)

    private val hello = line("Hello")
    private val there = line("Are you there")
    private val dinner = line("Dinner")

    @Test
    fun `the first words on the screen never buzz, however long the quiet before them`() {
        assertFalse(SpeechResume().noteCaptions(listOf(hello), now = 1_000.0))
    }

    @Test
    fun `a line that keeps growing is one stretch of talk, and only words after more than eight quiet seconds buzz`() {
        val resume = SpeechResume()
        resume.noteCaptions(listOf(hello), now = 100.0)
        val grown = hello.copy(text = "Hello there")
        assertFalse(resume.noteCaptions(listOf(grown), now = 107.9))
        assertFalse(resume.noteCaptions(listOf(grown, there), now = 115.9))
        assertTrue(resume.noteCaptions(listOf(grown, there, dinner), now = 124.4))
        assertFalse(resume.noteCaptions(listOf(grown, there, dinner.copy(text = "Dinner is ready")), now = 125.0))
    }

    @Test
    fun `captions redrawn without new words, for a speaker named or the status changing, neither buzz nor restart the quiet`() {
        val resume = SpeechResume()
        resume.noteCaptions(listOf(hello), now = 100.0)
        assertFalse(resume.noteCaptions(listOf(hello), now = 104.0))
        assertFalse(resume.noteCaptions(listOf(hello.copy(speakerClusterID = 2, isCommitted = true)), now = 106.0))
        assertTrue(resume.noteCaptions(listOf(hello, there), now = 108.5))
    }

    @Test
    fun `clearing the screen is her doing, not talk, so it never buzzes and the quiet counts from the clearing`() {
        val resume = SpeechResume()
        resume.noteCaptions(listOf(hello), now = 100.0)
        assertFalse(resume.noteCaptions(emptyList(), now = 130.0))
        assertFalse(resume.noteCaptions(listOf(there), now = 132.0))
        assertTrue(resume.noteCaptions(listOf(there, dinner), now = 140.5))
    }

    @Test
    fun `the same words said again on a new line are new talk`() {
        val resume = SpeechResume()
        val yes = line("yes")
        resume.noteCaptions(listOf(yes), now = 100.0)
        assertTrue(resume.noteCaptions(listOf(yes, line("yes")), now = 110.0))
    }
}
