package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietHoursTest {
    @Test
    fun `off by default, and off means never quiet whatever the hours are`() {
        assertFalse(QuietHours.default.isEnabled)
        val hours = QuietHours(isEnabled = false, startHour = 22, endHour = 7)
        assertFalse(hours.isQuiet(now = 0.0, utcOffsetSeconds = 0))
    }

    @Test
    fun `a window that wraps past midnight covers both sides of it`() {
        val hours = QuietHours(isEnabled = true, startHour = 22, endHour = 7)
        assertTrue(hours.isQuiet(now = 23 * 3_600.0, utcOffsetSeconds = 0))
        assertTrue(hours.isQuiet(now = 6 * 3_600.0, utcOffsetSeconds = 0))
        assertFalse(hours.isQuiet(now = 12 * 3_600.0, utcOffsetSeconds = 0))
        assertFalse(hours.isQuiet(now = 7 * 3_600.0, utcOffsetSeconds = 0))
        assertTrue(hours.isQuiet(now = 22 * 3_600.0, utcOffsetSeconds = 0))
    }

    @Test
    fun `a window within one day only covers that stretch`() {
        val hours = QuietHours(isEnabled = true, startHour = 13, endHour = 15)
        assertTrue(hours.isQuiet(now = 13 * 3_600.0, utcOffsetSeconds = 0))
        assertTrue(hours.isQuiet(now = 14 * 3_600.0, utcOffsetSeconds = 0))
        assertFalse(hours.isQuiet(now = 15 * 3_600.0, utcOffsetSeconds = 0))
        assertFalse(hours.isQuiet(now = 12 * 3_600.0, utcOffsetSeconds = 0))
    }

    @Test
    fun `equal start and end hours cover the whole day`() {
        val hours = QuietHours(isEnabled = true, startHour = 9, endHour = 9)
        assertTrue(hours.isQuiet(now = 0.0, utcOffsetSeconds = 0))
        assertTrue(hours.isQuiet(now = 20 * 3_600.0, utcOffsetSeconds = 0))
    }

    @Test
    fun `the timezone offset shifts which UTC hour counts as local`() {
        val hours = QuietHours(isEnabled = true, startHour = 22, endHour = 7)
        assertTrue(hours.isQuiet(now = 21 * 3_600.0, utcOffsetSeconds = 3 * 3_600))
        assertFalse(hours.isQuiet(now = 21 * 3_600.0, utcOffsetSeconds = -3 * 3_600))
    }

    @Test
    fun `on a real date in Israel's summer time, half past eleven at night and the minute before seven are quiet, noon and seven are not`() {
        val hours = QuietHours(isEnabled = true, startHour = 22, endHour = 7)
        val offset = 3 * 3_600
        assertTrue(hours.isQuiet(now = 1_791_577_800.0, utcOffsetSeconds = offset))
        assertFalse(hours.isQuiet(now = 1_791_536_400.0, utcOffsetSeconds = offset))
        assertTrue(hours.isQuiet(now = 1_791_604_799.0, utcOffsetSeconds = offset))
        assertFalse(hours.isQuiet(now = 1_791_604_800.0, utcOffsetSeconds = offset))
    }

    @Test
    fun `hours outside 0 to 23 are clamped, both from init and from decoding`() {
        val hours = QuietHours(isEnabled = true, startHour = -5, endHour = 99)
        assertEquals(0, hours.startHour)
        assertEquals(23, hours.endHour)

        val decoded = QuietHours.fromJson("""{"isEnabled":true,"startHour":-1,"endHour":30}""")
        assertEquals(0, decoded.startHour)
        assertEquals(23, decoded.endHour)
    }

    @Test
    fun `a settings file from before this existed decodes as off`() {
        assertEquals(QuietHours.default, QuietHours.fromJson("{}"))
    }
}
