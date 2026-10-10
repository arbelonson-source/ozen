package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavingTroubleNoticeTest {
    @Test
    fun `English title and detail`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Saving on the phone failed", SavingTroubleNotice.title)
            assertTrue(SavingTroubleNotice.detail.contains("iPhone Storage"))
        }
    }

    @Test
    fun `nothing to say while every save works`() {
        val notice = SavingTroubleNotice()
        assertFalse(notice.shouldShow)
        notice.update(settingsFailed = false, historyFailed = false)
        assertFalse(notice.shouldShow)
        assertFalse(notice.isFailing)
    }

    @Test
    fun `either kind of failed save shows it`() {
        val settings = SavingTroubleNotice()
        settings.update(settingsFailed = true, historyFailed = false)
        assertTrue(settings.shouldShow)

        val history = SavingTroubleNotice()
        history.update(settingsFailed = false, historyFailed = true)
        assertTrue(history.shouldShow)
    }

    @Test
    fun `dismissed, it stays away while saving keeps failing`() {
        val notice = SavingTroubleNotice()
        notice.update(settingsFailed = false, historyFailed = true)
        notice.dismiss()
        assertFalse(notice.shouldShow)
        notice.update(settingsFailed = true, historyFailed = true)
        assertFalse(notice.shouldShow)
        assertTrue(notice.isFailing)
    }

    @Test
    fun `once saving works again, a new failure shows it again`() {
        val notice = SavingTroubleNotice()
        notice.update(settingsFailed = true, historyFailed = false)
        notice.dismiss()
        notice.update(settingsFailed = false, historyFailed = false)
        assertFalse(notice.shouldShow)
        notice.update(settingsFailed = false, historyFailed = true)
        assertTrue(notice.shouldShow)
    }
}
