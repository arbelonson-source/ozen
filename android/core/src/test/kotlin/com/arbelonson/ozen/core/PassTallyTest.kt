package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PassTallyTest {
    @Test
    fun `says how long passes take and how much the filter threw away`() {
        val tally = PassTally()
        tally.recordLivePass(seconds = 0.5)
        tally.recordLivePass(seconds = 1.5)
        tally.recordFinalPass(cameBackEmpty = false)
        tally.recordFinalPass(cameBackEmpty = true)
        tally.recordSegments(seen = 5, accepted = 3)
        tally.recordSkippedWithoutVoice()
        assertEquals("live passes 2 avg 1.00s slowest 1.50s last 1.50s, final passes 2 (1 empty), segments 5 rejected by the filter 2, skipped with no voice 1", tally.summary)
    }

    @Test
    fun `a single pass has an average, and segments the filter kept whole reject nothing`() {
        val tally = PassTally()
        tally.recordLivePass(seconds = 0.8)
        tally.recordSegments(seen = 4, accepted = 4)
        tally.recordSegments(seen = 2, accepted = 3)
        assertEquals("live passes 1 avg 0.80s slowest 0.80s last 0.80s, final passes 0 (0 empty), segments 6 rejected by the filter 0, skipped with no voice 0", tally.summary)
    }

    @Test
    fun `before anything has run there is still a line, without made-up numbers`() {
        assertEquals("live passes 0 avg -s slowest 0.00s last -s, final passes 0 (0 empty), segments 0 rejected by the filter 0, skipped with no voice 0", PassTally().summary)
    }
}
