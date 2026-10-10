package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveAgreementTest {
    @Test
    fun `a line that only grows is shown exactly as the engine wrote it`() {
        val agreement = LiveAgreement()
        assertEquals("good morning", agreement.settle("good morning"))
        assertEquals("good morning  how did", agreement.settle("good morning  how did"))
        assertEquals("good morning how did you sleep", agreement.settle("good morning how did you sleep"))
    }

    @Test
    fun `a word two passes agreed on doesn't flip under the reader's eyes, while the end of the line keeps moving`() {
        val agreement = LiveAgreement()
        agreement.settle("I have an appointment at the")
        agreement.settle("I have an appointment at the doctor")
        val shown = agreement.settle("I have an ointment at the doctor at ten")
        assertEquals("I have an appointment at the doctor at ten", shown)
    }

    @Test
    fun `a word read the new way twice in a row is a correction - two pills that became three shows three`() {
        val agreement = LiveAgreement()
        agreement.settle("take two pills after the meal")
        agreement.settle("take two pills after the meal today")
        assertEquals("take two pills after the meal today", agreement.settle("take three pills after the meal today"))
        assertEquals("take three pills after the meal today please", agreement.settle("take three pills after the meal today please"))
        assertEquals("take three pills after the meal today please now", agreement.settle("take two pills after the meal today please now"))
    }

    @Test
    fun `a word seen only once was never agreed on, so the newer reading is shown`() {
        val agreement = LiveAgreement()
        agreement.settle("I have an ointment")
        assertEquals("I have an appointment at", agreement.settle("I have an appointment at"))
    }

    @Test
    fun `a pass that rewrites much of the line is a real change of mind and is shown`() {
        val agreement = LiveAgreement()
        agreement.settle("the cat sat on the mat")
        agreement.settle("the cat sat on the mat today")
        assertEquals("a bat spat at a hat today and", agreement.settle("a bat spat at a hat today and"))
        assertEquals("a bat spat at a hat today and then", agreement.settle("a bat spat at a hat today and then"))
    }

    @Test
    fun `a pass that drops words is shown, and what it dropped is no longer held`() {
        val agreement = LiveAgreement()
        agreement.settle("thank you thank you very much")
        agreement.settle("thank you thank you very much indeed")
        assertEquals("thank you very", agreement.settle("thank you very"))
        assertEquals("thank you very much", agreement.settle("thank you very much"))
    }

    @Test
    fun `a word or two of an agreed line is held, but a third changed word is a real change of mind`() {
        val line = "we will meet at the clinic on Sunday at ten"
        val agreement = LiveAgreement()
        agreement.settle(line)
        agreement.settle(line)
        assertEquals(line, agreement.settle("we will meet at the clinic on Monday at two"))
        val other = LiveAgreement()
        other.settle(line)
        other.settle(line)
        assertEquals("we will eat at the clinic on Monday at two", other.settle("we will eat at the clinic on Monday at two"))
    }

    @Test
    fun `changed words are held only while they are at most a third of the agreed ones`() {
        val agreement = LiveAgreement()
        agreement.settle("one two three four five six")
        agreement.settle("one two three four five six")
        assertEquals("one two three four five six", agreement.settle("one two tree four five sticks"))
        val shorter = LiveAgreement()
        shorter.settle("one two three four five")
        shorter.settle("one two three four five")
        assertEquals("one two tree four fives", shorter.settle("one two tree four fives"))
    }

    @Test
    fun `one changed word in a two-word agreed line is too much of it to hold, so the new reading is shown`() {
        val agreement = LiveAgreement()
        agreement.settle("good morning")
        agreement.settle("good morning")
        assertEquals("good evening", agreement.settle("good evening"))
        assertEquals("good evening everyone", agreement.settle("good evening everyone"))
    }

    @Test
    fun `an empty pass changes nothing`() {
        val agreement = LiveAgreement()
        agreement.settle("hello there")
        assertEquals("  ", agreement.settle("  "))
        assertEquals("hello there friend", agreement.settle("hello there friend"))
    }
}
