package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioTrackChoiceTest {
    @Test
    fun theTrackMarkedDefaultWinsElseTheFirstOne() {
        assertNull(AudioTrackChoice.index(emptyList()))
        assertEquals(0, AudioTrackChoice.index(listOf(false)))
        assertEquals(0, AudioTrackChoice.index(listOf(false, false)))
        assertEquals(1, AudioTrackChoice.index(listOf(false, true)))
        assertEquals(0, AudioTrackChoice.index(listOf(true, true)))
    }
}
