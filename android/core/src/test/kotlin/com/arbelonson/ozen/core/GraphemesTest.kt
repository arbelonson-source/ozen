package com.arbelonson.ozen.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphemesTest {
    private val standing = (0..0xFFFF).map { it.toChar() }.filter { Graphemes.standsAlone(it) }

    private fun boundaries(graphemes: Graphemes) = (0..graphemes.count).map { graphemes.start(it) }

    private fun pieces(text: String): List<String> = Graphemes.byBreakIterator(text).toList().zipWithNext { from, to -> text.substring(from, to) }

    @Test
    fun `every unit said to stand alone is a character of its own beside letters, digits, spaces, a new line and itself`() {
        val joined = ArrayList<String>()
        for (unit in standing) {
            val text = "a${unit}a$unit${unit}א${unit}5$unit $unit\n$unit"
            if (Graphemes.byBreakIterator(text).size != text.length + 1) joined += "U+%04X".format(unit.code)
        }
        assertTrue(joined.isEmpty(), "joined a neighbour: $joined")
        assertTrue(standing.size > 20_000)
        for (left in listOf("a", "א", "ب", "Ж", "中", "5", " ", "\n", ".")) assertTrue(left.all(Graphemes::standsAlone))
    }

    @Test
    fun `units that join their neighbours, or may, go to the BreakIterator`() {
        val joining = listOf('\r', '́', 'ָ', 'ּ', 'َ', 'ٰ', '؀', '۝', '‌', '‍', '️', '\uD83D', '\uDE00', 'ำ', '가', 'ᄀ')
        for (unit in joining) assertFalse(Graphemes.standsAlone(unit), "U+%04X".format(unit.code))
    }

    @Test
    fun `a text is split where the BreakIterator splits it, whether every unit stands alone or not`() {
        val random = Random(724)
        val others = listOf("́", "ָ", "ּ", "َ", "\r\n", "\r", "‍", "‌", "😀", "👍🏽", "🇮🇱", "️", "각", "ำ")
        repeat(3_000) {
            val text = buildString {
                repeat(random.nextInt(0, 40)) {
                    if (random.nextInt(8) == 0) append(others.random(random)) else append(standing.random(random))
                }
            }
            val graphemes = Graphemes.of(text)
            val expected = Graphemes.byBreakIterator(text).toList()
            assertEquals(expected, boundaries(graphemes), text)
            assertEquals(pieces(text), graphemes.strings(), text)
            assertEquals(pieces(text), (0 until graphemes.count).map { graphemes[it] }, text)
            if (Graphemes.splitsAtEveryUnit(text)) assertEquals((0..text.length).toList(), expected, text)
        }
    }

    @Test
    fun `a text whose units all stand alone is split at each of them, a Hebrew letter the same string every time`() {
        val text = "OK, נתראה מחר ב־10:30 ⁦050 123 4567⁩, مرحبا ٣ 你好。"
        assertTrue(Graphemes.splitsAtEveryUnit(text))
        val graphemes = Graphemes.of(text)
        assertEquals(text.length, graphemes.count)
        assertEquals(pieces(text), graphemes.strings())
        assertSame(Graphemes.of("נ").strings()[0], graphemes.strings()[4])
        assertTrue(graphemes.isOneOf(2, ".,"))
        assertFalse(graphemes.isOneOf(0, ".,"))
        assertEquals('נ'.code, graphemes.firstScalar(4))
    }

    @Test
    fun `a character of several units is one`() {
        val thumb = "\uD83D\uDC4D\uD83C\uDFFD"
        val text = "\u05E9\u05B8\u05C1\u05DC\u05D5\u05B9\u05DD $thumb!"
        val graphemes = Graphemes.of(text)
        assertEquals(pieces(text), graphemes.strings())
        assertEquals(listOf("\u05E9\u05B8\u05C1", "\u05DC", "\u05D5\u05B9", "\u05DD", " ", thumb, "!"), graphemes.strings())
        assertTrue(graphemes.isOneOf(6, "!"))
        assertFalse(graphemes.isOneOf(0, "ש"))
        assertEquals(text.length, graphemes.start(graphemes.count))
    }
}
