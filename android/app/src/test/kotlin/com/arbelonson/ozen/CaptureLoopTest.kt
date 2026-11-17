package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureLoopTest {
    private var clock = 0L
    private var version = 0
    private var open: () -> String? = { "next" }
    private var opens = 0
    private var reads = 0
    private var pauses = mutableListOf<Long>()
    private val retired = mutableListOf<String>()
    private val adopted = mutableListOf<String>()
    private var script: (String, Int) -> Int = { _, _ -> 100 }
    private var stopAfterReads = 5

    private fun loop() = CaptureLoop(
        first = "first",
        seen = 0,
        version = { version },
        open = {
            opens++
            open()
        },
        read = { record, _ ->
            reads++
            script(record, reads)
        },
        retire = { retired += it },
        adopted = { adopted += it },
        heard = { _, _ -> },
        running = { reads < stopAfterReads },
        now = { clock },
        pause = {
            pauses += it
            clock += it
        },
    )

    @Test
    fun aSwapThatThrowsDoesNotEscapeAndIsRetriedThenTheCaptureIsLost() {
        version = 1
        open = { throw IllegalStateException("projection revoked") }
        stopAfterReads = 10_000
        script = { _, _ ->
            clock += 100
            100
        }
        assertTrue(loop().run())
        assertEquals(CaptureLoop.MAX_SWAP_ATTEMPTS, opens)
        assertTrue(retired.isEmpty())
    }

    @Test
    fun aSwapThatReturnsNothingIsRetriedNotSilentlyKept() {
        version = 1
        var tries = 0
        open = { if (++tries < 3) null else "next" }
        stopAfterReads = 10_000
        script = { record, _ ->
            clock += 100
            if (record == "next") -1 else 100
        }
        val loop = loop()
        assertTrue(loop.run())
        assertEquals(3, opens)
        assertEquals(listOf("next"), adopted)
        assertEquals("next", loop.current)
        assertEquals(listOf("first"), retired)
    }

    @Test
    fun aSwapThatKeepsFailingRaisesLostRatherThanKeepingTheOldRecording() {
        version = 1
        open = { null }
        script = { _, _ ->
            clock += 100
            100
        }
        stopAfterReads = 10_000
        assertTrue(loop().run())
        assertEquals(CaptureLoop.MAX_SWAP_ATTEMPTS, opens)
    }

    @Test
    fun aGoodSwapIsDoneOnceAndTheOldRecordingRetired() {
        version = 1
        val loop = loop()
        assertFalse(loop.run())
        assertEquals(1, opens)
        assertEquals(listOf("first"), retired)
        assertEquals("next", loop.current)
    }

    @Test
    fun aNewlyPickedSourceGetsItsOwnFullSetOfTries() {
        version = 1
        stopAfterReads = 40
        script = { _, _ ->
            clock += 100
            100
        }
        open = {
            when (opens) {
                CaptureLoop.MAX_SWAP_ATTEMPTS - 1 -> {
                    version = 2
                    null
                }
                CaptureLoop.MAX_SWAP_ATTEMPTS -> null
                else -> if (opens < CaptureLoop.MAX_SWAP_ATTEMPTS) null else "second"
            }
        }
        val loop = loop()
        assertFalse(loop.run())
        assertEquals(listOf("second"), adopted)
        assertEquals("second", loop.current)
        assertEquals(listOf("first"), retired)
    }

    @Test
    fun aNewSourceRequestedDuringAPauseIsTriedAtOnce() {
        version = 1
        stopAfterReads = 5
        open = {
            if (opens == 1) {
                version = 2
                null
            } else {
                "second"
            }
        }
        val loop = loop()
        assertFalse(loop.run())
        assertEquals(2, opens)
        assertEquals("second", loop.current)
    }

    @Test
    fun emptyReadsSleepInsteadOfSpinning() {
        script = { _, _ -> 0 }
        stopAfterReads = 50
        assertFalse(loop().run())
        assertEquals(List(50) { CaptureLoop.EMPTY_READ_PAUSE_MS }, pauses)
    }
}
