package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryAdvisorTest {
    private fun run(readings: List<Pair<Float?, Boolean>>, advisor: BatteryAdvisor): List<BatteryWarning?> =
        readings.map { advisor.update(it.first, it.second) }

    @Test
    fun `a draining phone gets one low warning and one critical warning, not one per percent`() {
        val advisor = BatteryAdvisor()
        val levels = listOf(0.5f, 0.25f, 0.2f, 0.19f, 0.15f, 0.11f, 0.1f, 0.09f, 0.05f)
        val warnings = run(levels.map { it to false }, advisor).filterNotNull()
        assertEquals(listOf(BatteryWarning.Low(20), BatteryWarning.Critical(10)), warnings)
    }

    @Test
    fun `opening the app already at 8 percent goes straight to critical, with no stale low warning after`() {
        val advisor = BatteryAdvisor()
        val warnings = run(listOf(0.08f to false, 0.07f to false, 0.06f to false), advisor)
        assertEquals(listOf(BatteryWarning.Critical(8), null, null), warnings)
    }

    @Test
    fun `charging silences warnings, and running down again after a charge warns again`() {
        val advisor = BatteryAdvisor()
        advisor.update(0.18f, false)
        assertNull(advisor.update(0.18f, true))
        assertNull(advisor.update(0.6f, true))
        assertEquals(BatteryWarning.Low(19), advisor.update(0.19f, false))
    }

    @Test
    fun `a loose cable flickering at 9 percent doesn't send the urgent warning again with every flicker`() {
        val advisor = BatteryAdvisor()
        val readings = listOf(0.09f to false, 0.09f to true, 0.09f to false, 0.09f to true, 0.08f to false, 0.12f to true, 0.1f to false)
        assertEquals(listOf(BatteryWarning.Critical(9)), run(readings, advisor).filterNotNull())
        assertNull(advisor.update(0.16f, true))
        assertEquals(BatteryWarning.Critical(10), advisor.update(0.1f, false))
    }

    @Test
    fun `a level flickering around the threshold doesn't nag`() {
        val advisor = BatteryAdvisor()
        val readings = listOf(0.2f to false, 0.21f to false, 0.2f to false, 0.22f to false, 0.19f to false)
        assertEquals(listOf(BatteryWarning.Low(20)), run(readings, advisor).filterNotNull())
        assertNull(advisor.update(0.3f, false))
        assertEquals(BatteryWarning.Low(20), advisor.update(0.2f, false))
    }

    @Test
    fun `an unknown battery level never warns`() {
        val advisor = BatteryAdvisor()
        assertNull(advisor.update(null, false))
        assertNull(advisor.update(-1f, false))
    }

    @Test
    fun `the phone notification names the level - the 10 percent one replaces the 20 percent one and is urgent`() {
        Localization.withLanguage(UILanguage.Hebrew) {
            val low = BatteryWarning.Low(20).notificationContent
            val critical = BatteryWarning.Critical(9).notificationContent
            assertEquals("הסוללה ב-20%", low.title)
            assertEquals("הסוללה ב-9%", critical.title)
            assertEquals(critical.identifier, low.identifier)
            assertTrue(!low.isUrgent && critical.isUrgent)
            assertNotEquals(critical.body, low.body)
            assertTrue(critical.body.contains("עלול להיכבות"))
        }
    }

    @Test
    fun `the phone notification is in English when the app is`() {
        Localization.withLanguage(UILanguage.English) {
            val low = BatteryWarning.Low(20).notificationContent
            val critical = BatteryWarning.Critical(9).notificationContent
            assertEquals("Battery at 20%", low.title)
            assertEquals("Battery at 9%", critical.title)
            assertTrue(low.body.contains("plugging in"))
            assertTrue(critical.body.contains("Plug it in"))
        }
    }
}
