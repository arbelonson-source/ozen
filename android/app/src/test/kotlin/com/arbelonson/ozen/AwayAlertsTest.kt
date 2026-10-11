package com.arbelonson.ozen

import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.KeywordMatch
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.QuietHours
import com.arbelonson.ozen.core.UILanguage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwayAlertsTest {
    private val now = 1_800_000_000.0

    private fun hit(alert: UUID) = KeywordHit(segmentID = UUID.randomUUID(), match = KeywordMatch(alert, "סבתא", "סבתא", 0), timestamp = now)

    @Test
    fun `a word heard while the app is away notifies once in thirty seconds, never while it is in front`() {
        val grandma = UUID.randomUUID()
        val away = AwayAlerts()
        val settings = AppSettings.default
        Localization.withLanguage(UILanguage.English) {
            assertEquals(emptyList(), away.forWords(listOf(hit(grandma)), "Grandma, dinner is ready", appIsActive = true, settings, now, 0))
            val posted = away.forWords(listOf(hit(grandma)), "Grandma, dinner is ready", appIsActive = false, settings, now, 0)
            assertEquals(listOf("Said: Grandma"), posted.map { it.title })
            assertTrue(posted.single().body.contains("Grandma, dinner is ready"))
            assertEquals(emptyList(), away.forWords(listOf(hit(grandma)), "Grandma?", appIsActive = false, settings, now + 10, 0))
            assertEquals(1, away.forWords(listOf(hit(grandma)), "Grandma?", appIsActive = false, settings, now + 31, 0).size)
        }
    }

    @Test
    fun `nothing is posted with alerts off while the screen is off, or in quiet hours`() {
        val away = AwayAlerts()
        val off = AppSettings.default.also { it.notifyWhenInBackground = false }
        assertEquals(emptyList(), away.forWords(listOf(hit(UUID.randomUUID())), "Grandma", appIsActive = false, off, now, 0))
        val quiet = AppSettings.default.also { it.quietHours = QuietHours(isEnabled = true, startHour = 0, endHour = 0) }
        assertEquals(emptyList(), away.forWords(listOf(hit(UUID.randomUUID())), "Grandma", appIsActive = false, quiet, now, 0))
        assertEquals(1, away.forWords(listOf(hit(UUID.randomUUID())), "Grandma", appIsActive = false, AppSettings.default, now, 0).size)
    }
}
