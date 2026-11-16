package com.arbelonson.ozen.core

import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneNumbersWithoutUnicodeFlagTest {
    private fun callWord(): Pattern {
        val field = PhoneNumbers::class.java.getDeclaredField("callWordPattern")
        field.isAccessible = true
        val compiled = field.get(PhoneNumbers) as Pattern
        return Pattern.compile(compiled.pattern(), Pattern.CASE_INSENSITIVE)
    }

    private fun tappable(text: String): Boolean {
        val pattern = callWord()
        val run = Regex("""\d{3}[ ]\d{3}[ ]\d{4}""").find(text)!!
        return pattern.matcher(text.substring(0, run.range.first).takeLast(80)).find()
    }

    @Test
    fun `the call word pattern compiles on Android's engine and reads the same examples`() {
        assertTrue(tappable("call the doctor at 415 555 2671"))
        assertTrue(tappable("call Sarah 415 555 2671"))
        assertTrue(tappable("call \u00C9lodie 415 555 2671"))
        assertFalse(tappable("phone bill totals 250 300 1999"))
    }

    @Test
    fun `with the Unicode flag the same examples give the same answers`() {
        assertEquals(1, PhoneNumbers.matches("call the doctor at 415 555 2671").size)
        assertEquals(1, PhoneNumbers.matches("call Sarah 415 555 2671").size)
        assertEquals(1, PhoneNumbers.matches("call \u00C9lodie 415 555 2671").size)
        assertTrue(PhoneNumbers.matches("phone bill totals 250 300 1999").isEmpty())
    }
}
