package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FixedLinesSoFarTest {
    private val avi = WordFix("aval", "Avi")

    private fun heard(vararg texts: String) = texts.mapIndexed { index, text -> TranscribedLine(index * 5.0, index * 5.0 + 4, text) }

    private fun FixedLinesSoFar.texts(heard: List<TranscribedLine>, fixes: List<WordFix>) = of(heard, fixes).map { it.text }

    @Test
    fun theLinesMadeSoFarShowWithThePersonsWordFixes() {
        val heard = heard("I saw aval today", "then aval left")
        assertEquals(listOf("I saw Avi today", "then Avi left"), FixedLinesSoFar().texts(heard, listOf(avi)))
    }

    @Test
    fun aFixAddedMidRunReachesTheLinesAlreadyShownWithoutWaitingForANewLine() {
        val lines = FixedLinesSoFar()
        val heard = heard("I saw aval today", "then aval left")
        lines.of(heard.take(1), emptyList())
        assertEquals(listOf("I saw aval today", "then aval left"), lines.texts(heard, emptyList()))
        assertEquals(listOf("I saw Avi today", "then Avi left"), lines.texts(heard, listOf(avi)))
    }

    @Test
    fun aFixRemovedMidRunLeavesTheLinesAlreadyShownAsHeard() {
        val lines = FixedLinesSoFar()
        val heard = heard("I saw aval today", "then aval left")
        assertEquals(listOf("I saw Avi today", "then Avi left"), lines.texts(heard, listOf(avi)))
        assertEquals(listOf("I saw aval today", "then aval left"), lines.texts(heard, emptyList()))
    }

    @Test
    fun aFixWhoseWrittenFormHoldsItsHeardWordIsAppliedOnceToTheHeardWords() {
        val lines = FixedLinesSoFar()
        val cohen = WordFix("Avi", "Avi Cohen")
        val heard = heard("I saw Avi today", "Avi left")
        lines.of(heard.take(1), emptyList())
        assertEquals(listOf("I saw Avi Cohen today"), lines.texts(heard.take(1), listOf(cohen)))
        assertEquals(listOf("I saw Avi Cohen today", "Avi Cohen left"), lines.texts(heard, listOf(cohen)))
        assertEquals(listOf("I saw Avi Cohen today", "Avi Cohen left"), lines.texts(heard, listOf(cohen, avi)))
    }

    @Test
    fun progressWithNoNewLineAndTheSameListDoesNoFixingAndKeepsTheSameLines() {
        var fixed = 0
        val lines = FixedLinesSoFar { fixes, text -> fixed++; WordFixes.apply(fixes, text) }
        val heard = heard("one", "aval two", "three")
        lines.of(heard.take(1), listOf(avi))
        lines.of(heard.take(2), listOf(avi))
        val shown = lines.of(heard, listOf(avi))
        assertEquals(3, fixed, "each new line is fixed once, the earlier ones are not fixed again")
        repeat(50) { assertSame(shown, lines.of(heard, listOf(avi))) }
        assertEquals(3, fixed)
        assertEquals(listOf("one", "Avi 2", "three"), lines.texts(heard, listOf(avi, WordFix("two", "2"))))
        assertEquals(6, fixed, "a changed list fixes every line again, once")
    }
}
