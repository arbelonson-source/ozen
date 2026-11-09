package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinalPassWindowTest {
    private fun window(vararg passes: Pair<Int, Double>) = FinalPassWindow().apply {
        for ((frames, seconds) in passes) recordLivePass(frames, seconds)
    }

    private fun FinalPassWindow.usesFullWindow() = usesFullWindow(DeviceHeat.Nominal, lowPowerMode = false)

    @Test
    fun `before any live pass is timed a finished line keeps the margin`() {
        val window = FinalPassWindow()
        assertNull(window.fullWindowSeconds)
        assertFalse(window.usesFullWindow())
    }

    @Test
    fun `a phone whose live passes are fast reads the full window`() {
        val window = window(450 to 0.30, 450 to 0.31)
        assertEquals(1.03, window.fullWindowSeconds!!, 0.01)
        assertTrue(window.usesFullWindow())
    }

    @Test
    fun `a phone as slow as the emulator keeps the margin`() {
        val window = window(350 to 6.2, 200 to 3.5)
        assertTrue(window.fullWindowSeconds!! > 20.0)
        assertFalse(window.usesFullWindow())
    }

    @Test
    fun `the budget is a second and a half for the whole window`() {
        assertEquals(1.5, FinalPassWindow.BUDGET_SECONDS)
        assertTrue(window(450 to 0.44).usesFullWindow())
        assertFalse(window(450 to 0.46).usesFullWindow())
    }

    @Test
    fun `one slow pass among the last five drops back to the margin`() {
        val window = window(450 to 0.30, 450 to 0.30, 450 to 0.90, 450 to 0.30)
        assertFalse(window.usesFullWindow())
    }

    @Test
    fun `the margin lasts until the slow pass is older than the last five`() {
        val window = window(450 to 0.90)
        repeat(FinalPassWindow.REMEMBERED_PASSES - 1) {
            window.recordLivePass(450, 0.30)
            assertFalse(window.usesFullWindow(), "back to the full window after ${it + 1} fast passes")
        }
        window.recordLivePass(450, 0.30)
        assertTrue(window.usesFullWindow())
    }

    @Test
    fun `battery saver keeps the margin on a fast phone`() {
        assertFalse(window(450 to 0.30).usesFullWindow(DeviceHeat.Nominal, lowPowerMode = true))
    }

    @Test
    fun `a hot phone keeps the margin and a warm one does not`() {
        val window = window(450 to 0.30)
        assertTrue(window.usesFullWindow(DeviceHeat.Fair, lowPowerMode = false))
        assertFalse(window.usesFullWindow(DeviceHeat.Serious, lowPowerMode = false))
        assertFalse(window.usesFullWindow(DeviceHeat.Critical, lowPowerMode = false))
    }

    @Test
    fun `a pass timed at nothing or at no number is not a timing`() {
        val window = window(450 to 0.0, 450 to -1.0, 450 to Double.NaN, 450 to Double.POSITIVE_INFINITY, 0 to 0.3)
        assertNull(window.fullWindowSeconds)
        assertFalse(window.usesFullWindow())
    }
}
