package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NumberEmphasisEnglishTest {
    private fun emphasized(text: String, language: String = "en"): List<String> =
        NumberEmphasis.ranges(text, languageCode = language).map { text.substring(it.start, it.endExclusive) }

    @Test
    fun `pills and a clock time said in words`() {
        assertEquals(listOf("three pills", "ten thirty"), emphasized("Take three pills at ten thirty"))
        assertEquals(listOf("one pill", "five o'clock"), emphasized("Take one pill at five o'clock"))
        assertEquals(listOf("ten thirty pm"), emphasized("See you at ten thirty pm."))
    }

    @Test
    fun `how often a number of times a day, once or twice a day`() {
        assertEquals(listOf("twice a day"), emphasized("Take it twice a day"))
        assertEquals(listOf("once a day"), emphasized("Take it once a day with food"))
        assertEquals(listOf("Three times a day"), emphasized("Three times a day after meals"))
        assertEquals(listOf("one pill", "twice a week"), emphasized("Take one pill twice a week"))
    }

    @Test
    fun `every N hours stands out with its hours`() {
        assertEquals(listOf("eight hours"), emphasized("Every eight hours"))
        assertEquals(listOf("two weeks"), emphasized("Come back in two weeks"))
    }

    @Test
    fun `a half or a quarter of something, and the clock phrases`() {
        assertEquals(listOf("half a tablet"), emphasized("Take half a tablet"))
        assertEquals(listOf("Three quarters of a cup"), emphasized("Three quarters of a cup"))
        assertEquals(listOf("quarter past six"), emphasized("It is quarter past six"))
    }

    @Test
    fun `compound numbers and amounts`() {
        assertEquals(listOf("Two hundred milligrams"), emphasized("Two hundred milligrams"))
        assertEquals(listOf("one hundred and fifty dollars"), emphasized("It costs one hundred and fifty dollars"))
        assertEquals(listOf("twenty-three"), emphasized("The patient is twenty-three"))
        assertEquals(listOf("3 pills", "10:30"), emphasized("Take 3 pills at 10:30"))
    }

    @Test
    fun `one as a pronoun, and words that are not quantities here, stay plain`() {
        assertEquals(emptyList(), emphasized("The one I like is the red one"))
        assertEquals(emptyList(), emphasized("Which one is it"))
        assertEquals(emptyList(), emphasized("No one came, but one of the doctors called"))
        assertEquals(emptyList(), emphasized("Once I get there I will call"))
        assertEquals(emptyList(), emphasized("The first half was fine"))
        assertEquals(emptyList(), emphasized("Half of the people left"))
        assertEquals(emptyList(), emphasized("They are one on one"))
    }

    @Test
    fun `punctuation ends an amount before the next word`() {
        assertEquals(listOf("ten"), emphasized("At ten, pills are due"))
    }

    @Test
    fun `each language has its own word list, Hebrew and languages with no list are unchanged`() {
        assertEquals(emptyList(), emphasized("Take three pills", "he"))
        assertEquals(emptyList(), emphasized("prenez trois comprimés", "fr"))
        assertEquals(listOf("3"), emphasized("Take 3 pills", "fr"))
        assertEquals(listOf("שלושה כדורים", "בעשר"), emphasized("שלושה כדורים בעשר", "he"))
        assertEquals(listOf("3 pills", "שלושה כדורים"), emphasized("Take 3 pills, שלושה כדורים", "en"))
        assertEquals(listOf("3 pills"), emphasized("Take 3 pills", "en-US"))
    }

    @Test
    fun `a line is listed under numbers said by the same rules`() {
        assertTrue(NumberEmphasis.hasListableNumber("Take three pills", languageCode = "en"))
        assertTrue(NumberEmphasis.hasListableNumber("Twice a day", languageCode = "en"))
        assertFalse(NumberEmphasis.hasListableNumber("No one came", languageCode = "en"))
        assertFalse(NumberEmphasis.hasListableNumber("I took two", languageCode = "en"))
        assertFalse(NumberEmphasis.hasListableNumber("Take three pills", languageCode = "he"))
    }

    @Test
    fun `an English amount and what it counts are kept on one line when drawn`() {
        assertEquals("Take three\u00A0pills at nine", CaptionLayout.displayText("Take three pills at nine", "en"))
        assertEquals("Take three pills at nine", CaptionLayout.displayText("Take three pills at nine", "he"))
    }

    @Test
    fun `once every two days keeps the counted period`() {
        assertEquals(listOf("once every two days"), emphasized("Take it once every two days"))
    }

    @Test
    fun `one and a half is one amount`() {
        assertEquals(listOf("one and a half tablets"), emphasized("Take one and a half tablets"))
    }

    @Test
    fun `half past a number written in digits`() {
        assertEquals(listOf("half past 3"), emphasized("Meet at half past 3"))
    }
}
