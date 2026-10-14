package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun dialed(text: String): List<String> = PhoneNumbers.matches(text).map { it.dialable }

class PhoneNumbersTest {
    @Test
    fun `Israeli mobile and landline numbers, however they are spaced, dial as digits`() {
        assertEquals(listOf("0501234567"), dialed("תתקשרי לרופא 050-1234567 מחר"))
        assertEquals(listOf("031234567"), dialed("call 03 123 4567 please"))
        assertEquals(listOf("0521234567"), dialed("0521234567"))
        assertEquals(listOf("+972501234567"), dialed("+972 50 123 4567"))
        assertEquals(listOf("+97231234567"), dialed("+972-3-1234567"))
        assertEquals(listOf("0501234567", "048123456"), dialed("050-1234567 או 04-8123456"))
    }

    @Test
    fun `1-700 and 1-800 numbers dial, star numbers stay text, since iOS refuses to dial a link with a star`() {
        assertEquals(listOf("1700505050"), dialed("המוקד 1-700-50-50-50 פתוח"))
        assertEquals(listOf("1800123456"), dialed("1800123456"))
        assertEquals(listOf("1599123456", "0501234567"), dialed("1-599-123-456 או 050-1234567"))
        for (text in listOf("תתקשרי לקופה *2700", "1700", "בשנת 1800", "1-900-123-456", "*1-700-50-50-50")) {
            assertTrue(dialed(text).isEmpty(), text)
        }
    }

    @Test
    fun `the span covers exactly the number, so only it becomes tappable`() {
        val text = "הטלפון 050-1234567."
        val match = assertNotNull(PhoneNumbers.matches(text).firstOrNull())
        assertEquals("050-1234567", match.textIn(text))
        assertEquals("tel:0501234567", match.url)
    }

    @Test
    fun `times, doses, prices, dates, years and long numbers are not phone numbers`() {
        val texts = listOf(
            "בשעה 10:30", "3 כדורים פעמיים ביום", "500 מ״ג", "20%", "₪1500",
            "01/02/2026", "2026", "12345678", "1234567890", "05012345678901",
            "0012345678", "050--1234567", "5501234567",
        )
        for (text in texts) {
            assertTrue(dialed(text).isEmpty(), text)
        }
    }

    @Test
    fun `a number never takes a digit, a date or an hour said next to it, and a number one digit short or long is not linked`() {
        assertEquals(listOf("035551234"), dialed("המספר הוא 03-5551234 5 פעמים"))
        assertEquals(listOf("048123456"), dialed("תתקשרי 04-8123456 2 פעמים"))
        val texts = listOf(
            "התור הוא 09-03-2026 14:30", "09-03-2026 9:30", "הפגישה ב-05-03-2026 10:30",
            "050-123456", "03-12345678", "+972-50-123456", "050-1234567-8",
        )
        for (text in texts) {
            assertTrue(dialed(text).isEmpty(), text)
        }
    }

    @Test
    fun `the number is still found in the line as the caption screen draws it, with its direction marks`() {
        val shown = CaptionLayout.displayText("תתקשרי ל-050-1234567.")
        assertEquals(listOf("0501234567"), PhoneNumbers.matches(shown).map { it.dialable })
    }

    @Test
    fun `numbers written with no-break or narrow spaces still match and dial as digits`() {
        val nbsp = Char(0xA0)
        val narrow = Char(0x202F)
        assertEquals(listOf("0501234567"), dialed("050${nbsp}123${nbsp}4567"))
        assertEquals(listOf("0501234567"), dialed("050${narrow}123${narrow}4567"))
        assertEquals(listOf("+972501234567"), dialed("+972${nbsp}50${nbsp}123${nbsp}4567"))
        assertEquals(listOf("1800123456"), dialed("1${nbsp}800${nbsp}123${nbsp}456"))
    }

    @Test
    fun `a number written with other digits dials every digit, never a shorter number`() {
        val nbsp = Char(0xA0)
        assertEquals(listOf("1800123456"), dialed("1${nbsp}800${nbsp}123${nbsp}45\u0666"))
        assertEquals(listOf("0501234567"), dialed("050-123-456\u0667"))
    }

    @Test
    fun `a service number followed by a hyphen and a digit is longer than a service number, so it is no link`() {
        val nbsp = Char(0xA0)
        assertTrue(dialed("1${nbsp}800${nbsp}123${nbsp}456-7").isEmpty())
        assertTrue(dialed("1-800-123-456-7").isEmpty())
        assertEquals(listOf("1800123456"), dialed("1-800-123-456-"))
    }
}
