package com.arbelonson.ozen

import android.app.NotificationManager
import com.arbelonson.ozen.EmergencyChannelNotice.Warning
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmergencyChannelNoticeTest {
    private val none = NotificationManager.IMPORTANCE_NONE
    private val min = NotificationManager.IMPORTANCE_MIN
    private val low = NotificationManager.IMPORTANCE_LOW
    private val default = NotificationManager.IMPORTANCE_DEFAULT
    private val high = NotificationManager.IMPORTANCE_HIGH

    private fun warn(importance: Int?, sounds: Boolean = true, awayBanner: Boolean = false, notificationsOn: Boolean = true) =
        EmergencyChannelNotice.warning(sounds, awayBanner, notificationsOn, importance)

    @Test
    fun `a blocked Emergency channel warns that alerts are off`() {
        assertEquals(Warning.Off, warn(none))
    }

    @Test
    fun `a silent or minimal Emergency channel warns that alerts are silent`() {
        assertEquals(Warning.Silent, warn(low))
        assertEquals(Warning.Silent, warn(min))
    }

    @Test
    fun `a loud, new or unknown Emergency channel needs no warning`() {
        assertEquals(Warning.None, warn(default))
        assertEquals(Warning.None, warn(high))
        assertEquals(Warning.None, warn(null))
        assertEquals(Warning.None, warn(NotificationManager.IMPORTANCE_UNSPECIFIED))
    }

    @Test
    fun `sound and buzz both off at any importance count as silent, one of them off does not`() {
        val both = EmergencyChannelNotice.warning(true, false, true, high, hasSound = false, vibrates = false)
        assertEquals(Warning.Silent, both)
        assertEquals(Warning.None, EmergencyChannelNotice.warning(true, false, true, high, hasSound = false, vibrates = true))
        assertEquals(Warning.None, EmergencyChannelNotice.warning(true, false, true, default, hasSound = true, vibrates = false))
        assertEquals(false, AlertNotifier.alarmsOn(true, high, hasSound = false, vibrates = false))
        assertEquals(true, AlertNotifier.alarmsOn(true, high, hasSound = false, vibrates = true))
        assertEquals(false, AlertNotifier.alarmsGetThrough(true, high, true, hasSound = false, vibrates = false))
        assertEquals(true, AlertNotifier.alarmsGetThrough(true, default, true, hasSound = true, vibrates = false))
    }

    @Test
    fun `nothing is said while sound alerts are off`() {
        assertEquals(Warning.None, warn(none, sounds = false))
        assertEquals(Warning.None, warn(low, sounds = false))
    }

    @Test
    fun `with all notifications off the notifications-off banner speaks and this one does not repeat it`() {
        assertEquals(Warning.None, warn(high, awayBanner = true, notificationsOn = false))
        assertEquals(Warning.None, warn(none, awayBanner = true, notificationsOn = false))
        assertEquals(Warning.Off, warn(high, awayBanner = false, notificationsOn = false))
    }

    @Test
    fun `the notifications-off banner showing for the ordinary channel does not hide a silent Emergency channel`() {
        assertEquals(Warning.Silent, warn(low, awayBanner = true, notificationsOn = true))
        assertEquals(Warning.Off, warn(none, awayBanner = true, notificationsOn = true))
    }

    @Test
    fun `the words say what will happen to a smoke alarm`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Emergency alerts are muted", EmergencyChannelNotice.title())
        assertTrue(EmergencyChannelNotice.detail(Warning.Off).contains("turned off") && EmergencyChannelNotice.detail(Warning.Off).contains("Tap to turn them on"))
        assertTrue(EmergencyChannelNotice.detail(Warning.Silent).contains("silent") && EmergencyChannelNotice.detail(Warning.Silent).contains("Tap to fix"))
    }
}
