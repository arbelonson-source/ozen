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
            assertTrue(SavingTroubleNotice.detail.contains("Settings → Storage"))
        }
    }

    @Test
    fun `the storage menu is the one this phone really has`() {
        Localization.withLanguage(UILanguage.English) {
            assertTrue(SavingTroubleNotice.detail.contains("Settings → Storage"))
            assertFalse(SavingTroubleNotice.detail.contains("iPhone"))
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

    @Test
    fun `settings that can't be saved because their file can't be read right now are not blamed on a full phone`() {
        Localization.withLanguage(UILanguage.English) {
            val notice = SavingTroubleNotice()
            notice.update(settingsFailed = true, historyFailed = false, settingsUnreadable = true)
            assertTrue(notice.shouldShow)
            assertEquals(SavingTroubleNotice.unreadableSettingsDetail, notice.shownDetail)
            assertFalse(notice.shownDetail.contains("full"))
            assertTrue(notice.shownDetail.contains("can’t read its saved settings"))

            notice.update(settingsFailed = true, historyFailed = false)
            assertEquals(SavingTroubleNotice.detail, notice.shownDetail)

            notice.update(settingsFailed = true, historyFailed = true, settingsUnreadable = true)
            assertEquals(SavingTroubleNotice.detail, notice.shownDetail)

            notice.update(settingsFailed = false, historyFailed = true, settingsUnreadable = true)
            assertEquals(SavingTroubleNotice.detail, notice.shownDetail)
        }
    }

    @Test
    fun `the reason for settings that can't be read is said in Hebrew too`() {
        Localization.withLanguage(UILanguage.Hebrew) {
            assertTrue(SavingTroubleNotice.unreadableSettingsDetail.contains("לקרוא"))
            assertTrue(SavingTroubleNotice.unreadableSettingsDetail != SavingTroubleNotice.detail)
        }
    }
}
