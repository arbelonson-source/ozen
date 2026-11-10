package com.arbelonson.ozen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncementHoldTest {
    private val clockBefore = AnnouncementHold.now

    @AfterTest
    fun reset() {
        AnnouncementHold.now = clockBefore
        AnnouncementHold.heldUntil = 0L
    }

    @Test
    fun `something said during a safety alert's hold waits until the hold is over`() = runTest {
        AnnouncementHold.now = { testScheduler.currentTime }
        AnnouncementHold.heldUntil = 0L
        AnnouncementHold.holdFor(4_000)
        val saidAt = mutableListOf<Long>()
        launch {
            AnnouncementHold.waitOut()
            saidAt += testScheduler.currentTime
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(saidAt.isEmpty(), "said during the hold")
        AnnouncementHold.holdFor(1_000)
        advanceUntilIdle()
        assertEquals(listOf(4_000L), saidAt)
    }

    @Test
    fun `with no safety alert held it is said at once`() = runTest {
        AnnouncementHold.now = { testScheduler.currentTime }
        AnnouncementHold.heldUntil = 0L
        val saidAt = mutableListOf<Long>()
        launch {
            AnnouncementHold.waitOut()
            saidAt += testScheduler.currentTime
        }
        runCurrent()
        assertEquals(listOf(0L), saidAt)
    }
}
