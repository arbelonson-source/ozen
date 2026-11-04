package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordFixesReviewTest {
    private fun fix(heard: String, written: String) = WordFix(heard, written)

    @Test
    fun `correcting a word an earlier fix wrote also moves that earlier fix`() {
        val list = WordFixes.adding(fix("Avi", "Avy"), listOf(fix("aval", "Avi"), fix("x", "y")))
        assertEquals(listOf(fix("Avi", "Avy"), fix("aval", "Avy"), fix("x", "y")), list)
        assertEquals(listOf(fix("q", "r"), fix("aval", "Avi")), WordFixes.adding(fix("q", "r"), listOf(fix("aval", "Avi"))))
    }

    @Test
    fun `a phrase with punctuation inside it is refused, one with punctuation at its ends is kept`() {
        assertTrue(WordFixes.normalized(listOf(fix("St. Mary", "Saint Mary"))).isEmpty())
        assertTrue(WordFixes.normalized(listOf(fix("bat, sheva", "Batsheva"))).isEmpty())
        assertEquals(1, WordFixes.normalized(listOf(fix("bat sheva", "Batsheva"))).size)
        assertEquals(1, WordFixes.normalized(listOf(fix("bat sheva.", "Batsheva"))).size)
        assertEquals(1, WordFixes.normalized(listOf(fix("St.", "Saint"))).size)
    }

    @Test
    fun `Chinese words are fixed inside an unspaced sentence, once, without chaining`() {
        val list = listOf(fix("奶奶", "外婆"), fix("外婆", "爷爷"))
        assertEquals("外婆来了", WordFixes.apply(list, "奶奶来了"))
        assertEquals("我们去看外婆吧，外婆好", WordFixes.apply(list, "我们去看奶奶吧，奶奶好"))
        assertEquals("爷爷来了", WordFixes.apply(list, "爷爷来了"))
        assertEquals("doctor 大夫 ok", WordFixes.apply(listOf(fix("医生", "大夫")), "doctor 医生 ok"))
    }

    @Test
    fun `a case-only fix stays dropped`() {
        assertTrue(WordFixes.normalized(listOf(fix("avi", "Avi"))).isEmpty())
    }
}
