package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WhisperAudioContextTest {
    @Test
    fun `eight seconds of live sound get the window measured on the WhatsApp clips`() {
        assertEquals(450, WhisperAudioContext.frames(8 * 16_000, isFinal = false))
    }

    @Test
    fun `a part of a frame counts as a whole one`() {
        assertEquals(52, WhisperAudioContext.frames(321, isFinal = false))
        assertEquals(51, WhisperAudioContext.frames(320, isFinal = false))
    }

    @Test
    fun `a live pass as long as the window or longer gets the full window and no more`() {
        assertEquals(1_450, WhisperAudioContext.frames(28 * 16_000, isFinal = false))
        assertEquals(1_500, WhisperAudioContext.frames(29 * 16_000, isFinal = false))
        assertEquals(1_500, WhisperAudioContext.frames(30 * 16_000, isFinal = false))
        assertEquals(1_500, WhisperAudioContext.frames(45 * 16_000, isFinal = false))
    }

    @Test
    fun `a finished line keeps the full window, where the shorter one got more words wrong`() {
        assertEquals(1_500, WhisperAudioContext.frames(8 * 16_000, isFinal = true))
        assertEquals(1_500, WhisperAudioContext.frames(321, isFinal = true))
    }
}
