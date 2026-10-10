package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UncertainWordsTest {
    private val sure = -0.05f
    private val unsure = -2.5f

    @Test
    fun `the word the model guessed at is picked, the ones it knew are not`() {
        val picked = UncertainWords.pick(
            listOf(" The", " appointment", " is", " at", " 10:30."),
            listOf(listOf(sure), listOf(sure, sure), listOf(sure), listOf(sure), listOf(unsure, unsure, sure)),
        )
        assertEquals(listOf("1030"), picked)
    }

    @Test
    fun `a word whose first piece alone was a toss-up isn't marked`() {
        val picked = UncertainWords.pick(listOf(" tomorrow", " morning"), listOf(listOf(-1.6f, sure, sure), listOf(sure)))
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `when most of the line is doubtful the line's own mark says it, not every word`() {
        val picked = UncertainWords.pick(listOf(" a", " b", " c"), listOf(listOf(unsure), listOf(unsure), listOf(sure)))
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `punctuation on its own, missing scores and numbers that aren't numbers are passed over`() {
        assertTrue(
            UncertainWords.pick(
                listOf(" -", " word", " other", " third"),
                listOf(listOf(unsure), emptyList(), listOf(Float.NaN), listOf(sure)),
            ).isEmpty(),
        )
        assertTrue(UncertainWords.pick(emptyList(), emptyList()).isEmpty())
        assertTrue(UncertainWords.pick(listOf(" more", " words"), listOf(listOf(sure))).isEmpty())
    }

    @Test
    fun `the marked word is found in the line as shown, punctuation and all, and only as a whole word`() {
        val text = "The appointment is at 10:30, not at 10."
        val ranges = UncertainWords.ranges(text, listOf("1030", "appoint"))
        assertEquals(listOf("10:30,"), ranges.map { text.substring(it.start, it.endExclusive) })
        assertTrue(UncertainWords.ranges(text, emptyList()).isEmpty())
    }

    @Test
    fun `a word guessed at in one place and known in another is marked only where it was guessed`() {
        val text = "I know what I said, I think"
        val words = UncertainWords.pick(
            listOf(" I", " know", " what", " I", " said,", " I", " think"),
            listOf(listOf(sure), listOf(sure), listOf(sure), listOf(unsure), listOf(sure), listOf(sure), listOf(sure)),
        )
        assertEquals(listOf(12), UncertainWords.ranges(text, words).map { it.start })
        assertEquals(listOf("I"), UncertainWords.spoken(text, words))
    }

    @Test
    fun `a line made of several passes keeps each copy's mark in order`() {
        val text = "I know I said I would"
        val first = UncertainWords.pick(listOf(" I", " know", " I", " said"), listOf(listOf(sure), listOf(sure), listOf(unsure), listOf(sure)))
        val second = UncertainWords.pick(listOf(" I", " would"), listOf(listOf(unsure), listOf(sure)))
        assertEquals(listOf(7, 14), UncertainWords.ranges(text, first + second).map { it.start })
    }

    @Test
    fun `a word guessed at every time it was said is marked every time`() {
        val text = "no no, it was fine"
        val words = UncertainWords.pick(
            listOf(" no", " no,", " it", " was", " fine"),
            listOf(listOf(unsure), listOf(unsure), listOf(sure), listOf(sure), listOf(sure)),
        )
        assertEquals(listOf("no", "no,"), UncertainWords.ranges(text, words).map { text.substring(it.start, it.endExclusive) })
        assertEquals(listOf("no", "no,"), UncertainWords.ranges(text, listOf("no")).map { text.substring(it.start, it.endExclusive) })
    }

    @Test
    fun `VoiceOver hears the doubtful words as written, in order, without their punctuation`() {
        val text = "Meet the doctor at 10:30, room nine."
        val words = listOf(UncertainWords.normalize("nine."), UncertainWords.normalize("10:30,"))
        assertEquals(listOf("10:30", "nine"), UncertainWords.spoken(text, words))
        assertTrue(UncertainWords.spoken(text, emptyList()).isEmpty())
    }
}
