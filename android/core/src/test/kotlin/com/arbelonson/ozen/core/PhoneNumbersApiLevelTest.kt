package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PhoneNumbersApiLevelTest {
    @Test
    fun `phone number detection avoids calls that Android 13 and older lack`() {
        val stream = PhoneNumbers::class.java.getResourceAsStream("PhoneNumbers.class")
        assertNotNull(stream)
        val bytes = stream.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("results"), "Matcher.results needs Android 14")
        assertFalse(bytes.contains("toList") && bytes.contains("java/util/stream/Stream"), "Stream.toList needs Android 14")
    }

    @Test
    fun `two numbers in one line come back in the order they were said`() {
        val found = PhoneNumbers.matches("call 050-1234567 or 1-800-123-456")
        assertEquals(listOf("0501234567", "1800123456"), found.map { it.dialable })
        assertEquals(found.sortedBy { it.start }, found)
    }
}
