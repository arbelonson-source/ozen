package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakerColorSlotsTest {
    private fun slots(names: List<String>): List<Int> {
        val speakers = names.map { SpeakerColorSlots.Speaker.Named(it) }
        val result = SpeakerColorSlots(speakers)
        return speakers.map { result.slot(it) }
    }

    @Test
    fun `two saved people whose names hash alike still get different slots`() {
        val family = slots(listOf("Mom", "Dad"))
        assertNotEquals(family[0], family[1])
        val friends = slots(listOf("Dana", "Yossi"))
        assertNotEquals(friends[0], friends[1])
    }

    @Test
    fun `the names of the tests above do hash alike, so they test the slots and not luck`() {
        val alone = { name: String -> SpeakerColorSlots.aloneSlot(SpeakerColorSlots.Speaker.Named(name)) }
        assertEquals(alone("Mom"), alone("Dad"))
        assertEquals(alone("Dana"), alone("Yossi"))
    }

    @Test
    fun `one name plus four strangers fill all five slots`() {
        val speakers = listOf(
            SpeakerColorSlots.Speaker.Stranger(0),
            SpeakerColorSlots.Speaker.Named("Mom"),
            SpeakerColorSlots.Speaker.Stranger(1),
            SpeakerColorSlots.Speaker.Stranger(2),
            SpeakerColorSlots.Speaker.Stranger(3),
        )
        val result = SpeakerColorSlots(speakers)
        assertEquals(5, speakers.map { result.slot(it) }.toSet().size)
    }

    @Test
    fun `the same set gives the same slots every time`() {
        val speakers = listOf(
            SpeakerColorSlots.Speaker.Named("Mom"),
            SpeakerColorSlots.Speaker.Stranger(4),
            SpeakerColorSlots.Speaker.Named("Dad"),
            SpeakerColorSlots.Speaker.Stranger(1),
        )
        val first = SpeakerColorSlots(speakers)
        val second = SpeakerColorSlots(speakers)
        assertEquals(first, second)
        assertEquals(speakers.map { first.slot(it) }, speakers.map { second.slot(it) })
    }

    @Test
    fun `more than five speakers wrap and stay in range`() {
        val strangers = (0 until 9).map { SpeakerColorSlots.Speaker.Stranger(it) }
        val result = SpeakerColorSlots(strangers)
        for (speaker in strangers) assertTrue(result.slot(speaker) in 0 until 5)
        val named = SpeakerColorSlots((0 until 8).map { SpeakerColorSlots.Speaker.Named("person $it") })
        for (index in 0 until 8) assertTrue(named.slot(SpeakerColorSlots.Speaker.Named("person $index")) in 0 until 5)
    }

    @Test
    fun `a stranger whose id matches a saved name's slot does not share it`() {
        val result = SpeakerColorSlots(listOf(SpeakerColorSlots.Speaker.Named("Mom"), SpeakerColorSlots.Speaker.Stranger(2)))
        assertNotEquals(result.slot(SpeakerColorSlots.Speaker.Named("Mom")), result.slot(SpeakerColorSlots.Speaker.Stranger(2)))
    }

    @Test
    fun `generic labels and blank names count as strangers`() {
        assertEquals(SpeakerColorSlots.Speaker.Stranger(3), SpeakerColorSlots.speaker(3, EmbeddingClusterer.genericName(3)))
        assertEquals(SpeakerColorSlots.Speaker.Stranger(3), SpeakerColorSlots.speaker(3, ""))
        assertEquals(SpeakerColorSlots.Speaker.Stranger(3), SpeakerColorSlots.speaker(3, "  "))
        assertNull(SpeakerColorSlots.speaker(null, " "))
        assertNull(SpeakerColorSlots.speaker(null, null))
    }

    @Test
    fun `outside any screen a stranger keeps its id-based slot and a name its hashed one, the same numbers as the iPhone`() {
        assertEquals(2, SpeakerColorSlots.aloneSlot(SpeakerColorSlots.Speaker.Stranger(7)))
        assertEquals(2, SpeakerColorSlots.aloneSlot(SpeakerColorSlots.Speaker.Named("Mom")))
        assertEquals(3, SpeakerColorSlots.aloneSlot(SpeakerColorSlots.Speaker.Stranger(-2)))
    }

    @Test
    fun `a screen's people are taken in order of first appearance, by voice and saved name`() {
        val result = SpeakerColorSlots.of(listOf(4 to null, 1 to "Mom", 4 to null, null to null, 2 to "Dad"))
        assertEquals(result, SpeakerColorSlots(listOf(SpeakerColorSlots.Speaker.Stranger(4), SpeakerColorSlots.Speaker.Named("Mom"), SpeakerColorSlots.Speaker.Named("Dad"))))
        assertEquals(0, result.slot(4, null))
        assertNull(result.slot(null, null))
    }
}
