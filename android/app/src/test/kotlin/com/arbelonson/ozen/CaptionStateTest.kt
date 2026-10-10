package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptionStateTest {
    @Test
    fun `a line that grows replaces itself in place, and a new one goes below`() {
        CaptionState.clear()
        CaptionState.show(CaptionLine(1, "שלום", isFinal = false))
        CaptionState.show(CaptionLine(1, "שלום לכולם", isFinal = true))
        CaptionState.show(CaptionLine(2, "מה שלומך", isFinal = false))
        assertEquals(
            listOf(CaptionLine(1, "שלום לכולם", true), CaptionLine(2, "מה שלומך", false)),
            CaptionState.screen.value.lines,
        )
    }

    @Test
    fun `only the newest two hundred lines are kept on the screen`() {
        CaptionState.clear()
        for (id in 1..205) CaptionState.show(CaptionLine(id, "line $id", isFinal = true))
        val lines = CaptionState.screen.value.lines
        assertEquals(200, lines.size)
        assertEquals(6, lines.first().id)
        assertEquals(205, lines.last().id)
    }

    @Test
    fun `pausing keeps the lines already shown`() {
        CaptionState.clear()
        CaptionState.show(CaptionLine(1, "שלום", isFinal = true))
        CaptionState.setPhase(ListeningPhase.Paused)
        assertEquals(ListeningPhase.Paused, CaptionState.screen.value.phase)
        assertEquals(1, CaptionState.screen.value.lines.size)
    }
}
