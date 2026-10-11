package com.arbelonson.ozen

import com.arbelonson.ozen.core.EmbeddingClusterer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpeakerColourTest {
    private val grandma = "סבתא"

    @Test
    fun `a named speaker's colour follows the name, not the voice number this session gave them`() {
        assertEquals(SpeakerColour.of(0, grandma, light = false), SpeakerColour.of(7, grandma, light = false))
        assertEquals(SpeakerColour.of(0, grandma, light = true), SpeakerColour.of(7, grandma, light = true))
    }

    @Test
    fun `different names on one voice number are not all one colour`() {
        val names = listOf(grandma, "Dana", "Yossi", "Abba", "Ima")
        assertTrue(names.map { SpeakerColour.of(3, it, light = false) }.toSet().size > 1)
    }

    @Test
    fun `an unnamed voice and a generic speaker number are coloured by the voice`() {
        assertNotEquals(SpeakerColour.of(0, null, light = false), SpeakerColour.of(1, null, light = false))
        assertEquals(SpeakerColour.of(4, null, light = false), SpeakerColour.of(4, EmbeddingClusterer.genericName(4), light = false))
    }

    @Test
    fun `the white theme gets the deep version of each colour, and a line with no voice is grey`() {
        assertNotEquals(SpeakerColour.of(2, null, light = false), SpeakerColour.of(2, null, light = true))
        for (light in listOf(true, false)) {
            val grey = SpeakerColour.of(null, null, light)
            assertTrue(grey.red == grey.green && grey.green == grey.blue, "$grey")
        }
    }
}
