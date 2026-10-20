package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BigTextClearTest {
    private val message = "The pharmacy opens at nine.\nBring the blue folder. "
    private val newer = "Second thought: ten o'clock\nat the side door"

    @Test
    fun `clearing keeps the whole message exactly as typed, spaces and line breaks included, and an empty field gets it back`() {
        val stored = BigTextClear.cleared(message, null)
        assertEquals(message, stored)
        assertTrue(BigTextClear.restorable("", stored))
    }

    @Test
    fun `restore is offered only on an empty field with something stored`() {
        assertTrue(BigTextClear.restorable("", message))
        assertFalse(BigTextClear.restorable("", null))
        assertFalse(BigTextClear.restorable(message, message))
        assertFalse(BigTextClear.restorable(" ", message))
    }

    @Test
    fun `typing after a clear drops the stored text so clear then type then delete offers no restore`() {
        val afterTyping = BigTextClear.typed("x", message)
        assertNull(afterTyping)
        assertFalse(BigTextClear.restorable("x", afterTyping))
        assertFalse(BigTextClear.restorable("", BigTextClear.typed("", afterTyping)))
        assertEquals(message, BigTextClear.typed("", message))
    }

    @Test
    fun `a second clear after typing stores the new text and not the first one`() {
        val first = BigTextClear.cleared(message, null)
        val afterTyping = BigTextClear.typed(newer, first)
        val second = BigTextClear.cleared(newer, afterTyping)
        assertEquals(newer, second)
        assertEquals(newer, BigTextClear.cleared(newer, first))
        assertTrue(BigTextClear.restorable("", second))
    }

    @Test
    fun `a blank only field is never stored and an empty field keeps the earlier text`() {
        assertNull(BigTextClear.cleared("  \n ", null))
        assertEquals(message, BigTextClear.cleared("  \n ", message))
        assertEquals(message, BigTextClear.cleared("", message))
        assertNull(BigTextClear.cleared("", null))
    }
}
