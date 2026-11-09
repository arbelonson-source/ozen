package com.arbelonson.ozen

import android.app.NotificationManager
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightAlertsTest {
    private fun advice(manufacturer: String, brand: String = manufacturer) =
        Localization.withLanguage(UILanguage.English) { NightAlerts.makerAdvice(manufacturer, brand) }

    @Test
    fun `xiaomi advice points to battery saver and autostart`() {
        val text = advice("Xiaomi")
        assertNotNull(text)
        assertTrue(text.contains("No restrictions") && text.contains("Autostart"), text)
    }

    @Test
    fun `samsung advice points to unrestricted and the sleeping lists`() {
        val text = advice("samsung")
        assertNotNull(text)
        assertTrue(text.contains("Unrestricted") && text.contains("Deep sleeping"), text)
    }

    @Test
    fun `oppo advice points to battery usage`() {
        val text = advice("OPPO")
        assertNotNull(text)
        assertTrue(text.contains("Battery usage"), text)
    }

    @Test
    fun `tecno and infinix share advice about phone master`() {
        val tecno = advice("TECNO MOBILE LIMITED", "TECNO")
        assertNotNull(tecno)
        assertTrue(tecno.contains("Phone Master"), tecno)
        assertEquals(tecno, advice("INFINIX MOBILITY LIMITED", "Infinix"))
    }

    @Test
    fun `the brand alone can name the family`() {
        val redmi = advice("Unknown Maker", "Redmi")
        assertEquals(advice("Xiaomi"), redmi)
        assertNotNull(advice("HMD Global", "vivo"))
    }

    @Test
    fun `other makers and an empty maker get no advice`() {
        assertNull(advice("Google"))
        assertNull(advice("", ""))
    }

    @Test
    fun `samsung advice names the app as the sleeping lists show it`() {
        val text = Localization.withLanguage(UILanguage.Hebrew) { NightAlerts.makerAdvice("samsung", "samsung") }
        assertNotNull(text)
        assertTrue(text.contains("ש־Ozen לא ברשימות"), text)
    }

    @Test
    fun `open items count the battery and Do Not Disturb, not the maker menu`() {
        assertEquals(0, NightAlerts.openItems(batteryExempt = true, dndThrough = true, soundsOn = true))
        assertEquals(1, NightAlerts.openItems(batteryExempt = false, dndThrough = true, soundsOn = true))
        assertEquals(1, NightAlerts.openItems(batteryExempt = true, dndThrough = false, soundsOn = true))
        assertEquals(2, NightAlerts.openItems(batteryExempt = false, dndThrough = false, soundsOn = true))
    }

    @Test
    fun `sounds at home turned off is one more to check`() {
        assertEquals(1, NightAlerts.openItems(batteryExempt = true, dndThrough = true, soundsOn = false))
        assertEquals(3, NightAlerts.openItems(batteryExempt = false, dndThrough = false, soundsOn = false))
    }

    @Test
    fun `the sounds step says to leave Ozen listening, and to turn sounds on while they are off`() {
        Localization.withLanguage(UILanguage.English) {
            val off = NightAlerts.soundsText(on = false)
            assertTrue(off.contains("Turn on Sounds at home") && off.contains("leave Ozen listening"), off)
            val on = NightAlerts.soundsText(on = true)
            assertTrue(on.contains("leave Ozen listening", ignoreCase = true) && !on.contains("Turn on"), on)
        }
    }

    @Test
    fun `battery advice says what to pick in the list`() {
        Localization.withLanguage(UILanguage.English) {
            val text = NightAlerts.batteryText(exempt = false)
            assertTrue(text.contains("All apps") && text.contains("Ozen") && text.contains("Don’t optimize"), text)
            assertTrue(text.startsWith("To save battery, Android may stop Ozen"), text)
            assertEquals("Android lets Ozen keep running while the phone sleeps.", NightAlerts.batteryText(exempt = true))
        }
        val hebrew = Localization.withLanguage(UILanguage.Hebrew) { NightAlerts.batteryText(exempt = false) }
        assertTrue(hebrew.contains("מצאו את Ozen"), hebrew)
    }

    @Test
    fun `do not disturb advice names Emergency alerts turned off in the phone as the cause`() {
        val none = NotificationManager.IMPORTANCE_NONE
        val high = NotificationManager.IMPORTANCE_HIGH
        assertFalse(AlertNotifier.alarmsOn(notificationsOn = true, importance = none))
        assertFalse(AlertNotifier.alarmsOn(notificationsOn = false, importance = high))
        assertTrue(AlertNotifier.alarmsOn(notificationsOn = true, importance = high))
        assertTrue(AlertNotifier.alarmsOn(notificationsOn = true, importance = null))
        Localization.withLanguage(UILanguage.English) {
            val off = NightAlerts.dndText(through = false, alarmsAllowed = false)
            assertTrue(off.contains("turned off") && off.contains("Turn them on"), off)
            val override = NightAlerts.dndText(through = false, alarmsAllowed = true)
            assertTrue(!override.contains("turned off") && override.contains("override"), override)
            assertEquals("Urgent sounds, like a smoke alarm, come through Do Not Disturb.", NightAlerts.dndText(through = true, alarmsAllowed = true))
        }
    }

    @Test
    fun `the row says all set or how many to check`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("All set", NightAlerts.summary(0))
            assertEquals("1 to check", NightAlerts.summary(1))
            assertEquals("2 to check", NightAlerts.summary(2))
        }
    }
}
