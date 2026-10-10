package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationBreakTest {
    @Test
    fun `a conversation with nothing saved yet is never split`() {
        assertFalse(ConversationBreak.shouldStartNew(lastCaptionAt = null, now = 1_000_000.0))
    }

    @Test
    fun `a pause in talking is still the same conversation`() {
        assertFalse(ConversationBreak.shouldStartNew(lastCaptionAt = 1_000.0, now = 1_000.0 + 19 * 60))
    }

    @Test
    fun `twenty quiet minutes start a new one`() {
        assertTrue(ConversationBreak.shouldStartNew(lastCaptionAt = 1_000.0, now = 1_000.0 + 20 * 60))
    }

    @Test
    fun `a conversation starts when listening began, unless its first line came a break's worth of quiet later`() {
        val night = 1_000_000.0
        assertEquals(night, ConversationBreak.start(listeningSince = night, firstLineAt = night + 5 * 60))
        assertEquals(night + 9.5 * 3_600, ConversationBreak.start(listeningSince = night, firstLineAt = night + 9.5 * 3_600))
        assertEquals(night, ConversationBreak.start(listeningSince = night, firstLineAt = 1.0))
        assertEquals(night, ConversationBreak.start(listeningSince = night, firstLineAt = null))
        assertEquals(night, ConversationBreak.start(listeningSince = null, firstLineAt = night))
        assertNull(ConversationBreak.start(listeningSince = null, firstLineAt = null))
    }
}
