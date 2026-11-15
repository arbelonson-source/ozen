package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryingModelTest {
    private var clock = 0L
    private var loads = 0
    private var failTimes = 1
    private val notes = mutableListOf<String>()

    private val model = RetryingModel(
        name = "voice model",
        load = {
            loads += 1
            if (loads <= failTimes) error("no model") else "ready"
        },
        nowMillis = { clock },
        note = { notes += it },
    )

    @Test
    fun `a model that failed once comes back after the gap, not before`() {
        assertNull(model.get())
        clock = 59_000
        assertNull(model.get())
        assertEquals(1, loads)
        clock = 60_000
        assertEquals("ready", model.get())
        assertEquals("ready", model.get())
        assertEquals(2, loads)
    }

    @Test
    fun `one journal line per failure and one when it recovers`() {
        model.get()
        clock = 61_000
        model.get()
        assertEquals(2, notes.size)
        assertEquals("voice model failed to load (try 1), trying again in 60 s", notes[0])
        assertEquals("voice model loaded again after 1 failed tries", notes[1])
    }

    @Test
    fun `it stops trying after the last gap`() {
        failTimes = 100
        for (at in listOf(0L, 60_000L, 180_000L, 420_000L, 10_000_000L)) {
            clock = at
            model.get()
        }
        assertEquals(4, loads)
        assertEquals("voice model failed to load (try 4), giving up until the app restarts", notes.last())
    }

    @Test
    fun `running out of memory while loading counts as a failed load, not a crash`() {
        val big = RetryingModel<String>("voice model", { throw OutOfMemoryError("buffer") }, { clock }, { notes += it })
        assertNull(big.get())
        assertEquals("voice model failed to load (try 1), trying again in 60 s", notes.single())
    }
}
