package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.FileTranscriptionProblem
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun everyRefusalCountsSoTheNoteIsAnnouncedAgainWhileItStaysOneNote() {
        val notice = BusyNotice()
        notice.accept(busy = true)
        assertEquals(1, notice.refusals.value)
        notice.accept(busy = true)
        assertEquals(2, notice.refusals.value)
        assertTrue(notice.shown.value)
    }

    @Test
    fun theCountStartsAgainOnceTheNoteIsGone() {
        val notice = BusyNotice()
        notice.accept(busy = true)
        notice.accept(busy = true)
        notice.clear()
        assertEquals(0, notice.refusals.value)
        notice.accept(busy = true)
        notice.follow(failed)
        assertEquals(0, notice.refusals.value)
        notice.accept(busy = true)
        assertEquals(1, notice.refusals.value)
    }

    @Test
    fun theLineIsDueOncePerRefusalNotWhenThePageIsShownAgain() {
        val notice = BusyNotice()
        notice.accept(busy = true)
        assertTrue(notice.announcementDue())
        assertFalse(notice.announcementDue())
        notice.accept(busy = true)
        assertTrue(notice.announcementDue())
        assertFalse(notice.announcementDue())
    }

    @Test
    fun theLineIsDueAgainAfterTheNoteWasCleared() {
        val notice = BusyNotice()
        notice.accept(busy = true)
        assertTrue(notice.announcementDue())
        notice.clear()
        assertFalse(notice.announcementDue())
        notice.accept(busy = true)
        assertTrue(notice.announcementDue())
    }

    @Test
    fun anIdleShareCountsNothing() {
        val notice = BusyNotice()
        notice.accept(busy = false)
        assertEquals(0, notice.refusals.value)
    }

    private val ready = FileTranscription.Ready("a.mp3", UILanguage.English, null, emptyList(), PhoneModelCatalog.all.first(), null, false)
    private val failed = FileTranscription.Failed("a.mp3", FileTranscriptionProblem.Unexpected)

    @Test
    fun theNoticeStaysWhileTheFirstRecordingStillRuns() {
        val notice = BusyNotice()
        notice.accept(busy = true)
        notice.follow(working())
        assertTrue(notice.shown.value)
    }

    @Test
    fun theNoticeGoesWhenTheRunFailsAndWhenAReadyPageIsShown() {
        val notice = BusyNotice()
        notice.accept(busy = true)
        notice.follow(failed)
        assertFalse(notice.shown.value)
        notice.accept(busy = true)
        notice.follow(ready)
        assertFalse(notice.shown.value)
    }

    @Test
    fun aNewRunStartingAfterATryAgainShowsNoOldNotice() {
        val notice = BusyNotice()
        notice.accept(busy = true)
        notice.follow(failed)
        notice.follow(ready)
        notice.follow(working())
        assertFalse(notice.shown.value)
    }

    private fun working() = FileTranscription.Working("a.mp3", UILanguage.English, PhoneModelCatalog.all.first(), FileTranscription.Working.Stage.Loading, null, null, emptyList())
}
