package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundAlertPolicyTest {
    private fun sound(identifier: String): SoundAlert {
        val event = SoundEventCatalog.event(identifier)!!
        return SoundAlert(event = event, confidence = 0.9, timestamp = 0.0)
    }

    private fun hit(phrase: String, alertID: UUID = UUID.randomUUID()) = KeywordHit(
        segmentID = UUID.randomUUID(),
        match = KeywordMatch(alertID = alertID, phrase = phrase, matchedText = phrase, wordIndex = 0),
        timestamp = 0.0,
    )

    @Test
    fun `nothing is posted while the app is on screen - the banner is already there`() {
        val policy = BackgroundAlertPolicy()
        assertNull(policy.notification(sound("door_bell"), appIsActive = true, now = 0.0))
        assertNull(policy.notification(hit("סבתא"), "סבתא בואי", appIsActive = true, now = 0.0))
    }

    @Test
    fun `in the background a sound becomes a notification, and a siren is marked urgent`() {
        val policy = BackgroundAlertPolicy()
        val bell = policy.notification(sound("door_bell"), appIsActive = false, now = 0.0)
        assertEquals(SoundEventCatalog.event("door_bell")?.name, bell?.title)
        assertEquals(false, bell?.isUrgent)
        val siren = policy.notification(sound("civil_defense_siren"), appIsActive = false, now = 0.0)
        assertEquals(true, siren?.isUrgent)
        assertEquals("sounds", siren?.threadIdentifier)
        val sample = BackgroundAlertPolicy.testNotification
        assertTrue(!sample.isUrgent && sample.threadIdentifier == bell?.threadIdentifier)
    }

    @Test
    fun `the same sound or word notifies once per cooldown, different ones independently`() {
        val policy = BackgroundAlertPolicy(cooldownSeconds = 30.0)
        val alertID = UUID.randomUUID()
        assertNotNull(policy.notification(hit("סבתא", alertID), "סבתא", appIsActive = false, now = 100.0))
        assertNull(policy.notification(hit("סבתא", alertID), "סבתא שוב", appIsActive = false, now = 110.0))
        assertNotNull(policy.notification(hit("אקמול"), "אקמול", appIsActive = false, now = 111.0))
        assertNotNull(policy.notification(hit("סבתא", alertID), "סבתא", appIsActive = false, now = 131.0))
    }

    @Test
    fun `two sounds the catalog shows as the same sound share one cooldown, not two`() {
        val policy = BackgroundAlertPolicy(cooldownSeconds = 30.0)
        assertNotNull(policy.notification(sound("telephone_bell_ringing"), appIsActive = false, now = 0.0))
        assertNull(policy.notification(sound("ringtone"), appIsActive = false, now = 5.0))
    }

    @Test
    fun `a clock set backward doesn't extend the cooldown or suppress a genuinely new alert`() {
        val policy = BackgroundAlertPolicy(cooldownSeconds = 30.0)
        assertNotNull(policy.notification(sound("door_bell"), appIsActive = false, now = 100_000.0))
        assertNotNull(policy.notification(sound("door_bell"), appIsActive = false, now = 99_400.0))
    }

    @Test
    fun `quiet hours mute a keyword or ordinary sound but never a critical one`() {
        val policy = BackgroundAlertPolicy(quietHours = QuietHours(isEnabled = true, startHour = 22, endHour = 7))
        val insideWindow = 23.0 * 3_600
        assertNull(policy.notification(sound("door_bell"), appIsActive = false, now = insideWindow, utcOffsetSeconds = 0))
        assertNull(policy.notification(hit("סבתא"), "סבתא", appIsActive = false, now = insideWindow, utcOffsetSeconds = 0))
        assertNotNull(policy.notification(sound("civil_defense_siren"), appIsActive = false, now = insideWindow, utcOffsetSeconds = 0))

        val outsideWindow = 12.0 * 3_600
        assertNotNull(policy.notification(sound("door_bell"), appIsActive = false, now = outsideWindow, utcOffsetSeconds = 0))
        assertNotNull(policy.notification(hit("אקמול"), "אקמול", appIsActive = false, now = outsideWindow, utcOffsetSeconds = 0))
    }

    @Test
    fun `turned off, nothing is ever posted`() {
        val policy = BackgroundAlertPolicy(isEnabled = false)
        assertNull(policy.notification(sound("door_bell"), appIsActive = false, now = 0.0))
    }

    @Test
    fun `a keyword notification quotes the line, cut at a whole word`() {
        val policy = BackgroundAlertPolicy()
        val content = policy.notification(hit("סבתא"), "  סבתא, בואי לאכול ", appIsActive = false, now = 0.0)
        assertEquals("נאמר: סבתא", content?.title)
        assertEquals("סבתא, בואי לאכול", content?.body)
        val english = policy.notification(hit("סבתא"), "OK סבתא, בואי", appIsActive = false, now = 100.0)
        assertEquals("‏OK סבתא, בואי", english?.body)

        val long = "מילה ".repeat(60)
        val cut = BackgroundAlertPolicy.excerpt(long)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.length <= 121)
        assertEquals("מילה ".repeat(23) + "מילה…", cut)
        assertEquals("קצר", BackgroundAlertPolicy.excerpt(" קצר\n"))
        assertEquals("א".repeat(120), BackgroundAlertPolicy.excerpt("א".repeat(120)))
        assertTrue(cut.dropLast(1).endsWith("מילה"))
    }

    @Test
    fun `a phone number quoted in a keyword notification reads left to right on the lock screen, as on the caption screen`() {
        val policy = BackgroundAlertPolicy()
        val content = policy.notification(hit("סבתא"), "סבתא תתקשרי 050 123 4567 מחר", appIsActive = false, now = 0.0)
        assertEquals("סבתא תתקשרי ⁦050 123 4567⁩ מחר", content?.body)
    }

    @Test
    fun `the same sound shares one cooldown in English too, where its two labels read differently`() {
        Localization.withLanguage(UILanguage.English) {
            val policy = BackgroundAlertPolicy(cooldownSeconds = 30.0)
            assertNotNull(policy.notification(sound("telephone_bell_ringing"), appIsActive = false, now = 0.0))
            assertNull(policy.notification(sound("ringtone"), appIsActive = false, now = 5.0))
            assertNotNull(policy.notification(sound("boiling"), appIsActive = false, now = 5.0))
            assertNull(policy.notification(sound("whistling"), appIsActive = false, now = 10.0))
        }
    }

    @Test
    fun `notification bodies are in English when the app is`() {
        Localization.withLanguage(UILanguage.English) {
            val policy = BackgroundAlertPolicy()
            val bell = policy.notification(sound("door_bell"), appIsActive = false, now = 0.0)
            assertEquals("Doorbell", bell?.title)
            assertEquals("Heard just now near the phone.", bell?.body)
            val siren = policy.notification(sound("civil_defense_siren"), appIsActive = false, now = 0.0)
            assertEquals("Attention! Heard just now near the phone.", siren?.body)

            val hitContent = policy.notification(hit("grandma"), "grandma", appIsActive = false, now = 100.0)
            assertEquals("Said: grandma", hitContent?.title)
            val suggested = policy.notification(hit("סבתא"), "סבתא", appIsActive = false, now = 200.0)
            assertEquals("Said: Grandma", suggested?.title)

            assertEquals("Test: doorbell", BackgroundAlertPolicy.testNotification.title)
        }
    }

    @Test
    fun `permission is asked only while alerts are on and nobody has answered - a no is never asked again`() {
        assertTrue(BackgroundAlertPolicy.shouldAskPermission(alertsWhenScreenOff = true, allowed = null))
        assertFalse(BackgroundAlertPolicy.shouldAskPermission(alertsWhenScreenOff = true, allowed = false))
        assertFalse(BackgroundAlertPolicy.shouldAskPermission(alertsWhenScreenOff = true, allowed = true))
        assertFalse(BackgroundAlertPolicy.shouldAskPermission(alertsWhenScreenOff = false, allowed = null))
    }
}
