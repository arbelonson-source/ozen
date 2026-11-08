package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `open items count the battery and Do Not Disturb, not the maker menu`() {
        assertEquals(0, NightAlerts.openItems(batteryExempt = true, dndThrough = true))
        assertEquals(1, NightAlerts.openItems(batteryExempt = false, dndThrough = true))
        assertEquals(1, NightAlerts.openItems(batteryExempt = true, dndThrough = false))
        assertEquals(2, NightAlerts.openItems(batteryExempt = false, dndThrough = false))
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
