package com.arbelonson.ozen

import com.arbelonson.ozen.core.BatteryWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryWatchTest {
    private var shown: BatteryWarning? = null
    private val warned = mutableListOf<BatteryWarning>()
    private val watch = BatteryWatch(show = { shown = it }, warned = { warned += it })

    @Test
    fun `nothing is said while nothing listens`() {
        watch.reading(0.15f, pluggedIn = false, listening = false)
        assertNull(shown)
        assertEquals(emptyList(), warned)
    }

    @Test
    fun `while listening it says so once at twenty percent and again at ten`() {
        watch.reading(0.5f, pluggedIn = false, listening = true)
        assertNull(shown)
        watch.reading(0.19f, pluggedIn = false, listening = true)
        assertEquals(BatteryWarning.Low(19), shown)
        watch.reading(0.18f, pluggedIn = false, listening = true)
        assertEquals(listOf<BatteryWarning>(BatteryWarning.Low(19)), warned)
        watch.reading(0.09f, pluggedIn = false, listening = true)
        assertEquals(BatteryWarning.Critical(9), shown)
        assertEquals(listOf(BatteryWarning.Low(19), BatteryWarning.Critical(9)), warned)
    }

    @Test
    fun `plugging in or the listening stopping takes the warning down`() {
        watch.reading(0.19f, pluggedIn = false, listening = true)
        watch.reading(0.19f, pluggedIn = true, listening = true)
        assertNull(shown)
        watch.reading(0.30f, pluggedIn = false, listening = true)
        watch.reading(0.19f, pluggedIn = false, listening = true)
        assertEquals(BatteryWarning.Low(19), shown)
        watch.listeningChanged(false)
        assertNull(shown)
        watch.listeningChanged(false)
        assertEquals(2, warned.size)
    }

    @Test
    fun `only listening starting asks for a reading`() {
        assertEquals(false, watch.listeningChanged(false))
        assertEquals(true, watch.listeningChanged(true))
        assertEquals(false, watch.listeningChanged(true))
    }
}
