package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusyNoticeTest {
    @Test
    fun aRecordingSharedWhileAnotherRunsRaisesTheNoticeAndIsNotTaken() {
        val notice = BusyNotice()
        assertFalse(notice.accept(busy = true))
        assertTrue(notice.shown.value)
    }

    @Test
    fun aRecordingSharedWhenIdleIsTakenWithoutNotice() {
        val notice = BusyNotice()
        assertTrue(notice.accept(busy = false))
        assertFalse(notice.shown.value)
    }

    @Test
    fun takingARecordingOrClearingRemovesAStaleNotice() {
        val notice = BusyNotice()
        notice.accept(busy = true)
        notice.clear()
        assertFalse(notice.shown.value)
        notice.accept(busy = true)
        notice.accept(busy = false)
        assertFalse(notice.shown.value)
    }
}
