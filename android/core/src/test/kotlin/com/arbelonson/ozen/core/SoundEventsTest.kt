package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun reading(id: String, confidence: Double = 0.9, at: Double = 100.0) =
    SoundObservation(identifier = id, confidence = confidence, timestamp = at)

private fun alert(identifier: String): SoundAlert =
    SoundAlert(event = assertNotNull(SoundEventCatalog.event(identifier)), confidence = 0.9, timestamp = 1.0)

class SoundEventsTest {
    @Test
    fun `what a buzzing phone can be taken for is never a safety sound or the doorbell, and every name is a real sound`() {
        for (identifier in SoundEventCatalog.vibrationLookalikes) {
            val event = SoundEventCatalog.event(identifier)
            assertNotNull(event, "$identifier is not in the catalog")
            assertTrue(event.importance != SoundEvent.Importance.Critical)
            assertTrue(identifier != "door_bell")
        }
    }

    @Test
    fun `catalog identifiers are unique, snake_case, and speech is deliberately absent`() {
        val identifiers = SoundEventCatalog.events.map { it.identifier }
        assertEquals(identifiers.size, identifiers.toSet().size)
        for (identifier in identifiers) {
            assertEquals(identifier.lowercase(), identifier, identifier)
            assertFalse(identifier.contains(" "), identifier)
        }
        assertNull(SoundEventCatalog.event("speech"))
        assertNull(SoundEventCatalog.event("whispering"))
        assertEquals(SoundEvent.Importance.Critical, SoundEventCatalog.event("civil_defense_siren")?.importance)
        assertEquals("פעמון דלת", SoundEventCatalog.event("door_bell")?.name)
    }

    @Test
    fun `a classifier window's candidates are kept by catalog membership, not by rank, so a doorbell buried under speech and chatter still gets through`() {
        val candidates = listOf(
            "speech" to 0.95,
            "chatter" to 0.85,
            "singing" to 0.7,
            "whispering" to 0.65,
            "door_bell" to 0.62,
        )
        val observations = SoundEventCatalog.matchingObservations(candidates, minimumConfidence = 0.6, timestamp = 42.0)
        assertEquals(listOf("door_bell"), observations.map { it.identifier })
        assertEquals(0.62, observations.first().confidence)
        assertEquals(42.0, observations.first().timestamp)
    }

    @Test
    fun `several catalog sounds in the same window are all kept, and a candidate below the floor is dropped even if it's a catalog sound`() {
        val candidates = listOf(
            "door_bell" to 0.7,
            "smoke_detector" to 0.61,
            "cat" to 0.2,
        )
        val observations = SoundEventCatalog.matchingObservations(candidates, minimumConfidence = 0.6, timestamp = 1.0)
        assertEquals(setOf("door_bell", "smoke_detector"), observations.map { it.identifier }.toSet())
    }

    @Test
    fun `in one reading the most important sound comes first, so a smoke alarm also heard as a louder alarm clock sends one notification`() {
        val candidates = listOf(
            "alarm_clock" to 0.9,
            "door_bell" to 0.8,
            "smoke_detector" to 0.7,
            "fire" to 0.75,
        )
        val observations = SoundEventCatalog.matchingObservations(candidates, minimumConfidence = 0.6, timestamp = 1.0)
        assertEquals(listOf("fire", "smoke_detector", "alarm_clock", "door_bell"), observations.map { it.identifier })
    }

    @Test
    fun `importance orders critical above high above medium above low`() {
        assertTrue(SoundEvent.Importance.Critical > SoundEvent.Importance.High)
        assertTrue(SoundEvent.Importance.High > SoundEvent.Importance.Medium)
        assertTrue(SoundEvent.Importance.Medium > SoundEvent.Importance.Low)
        assertEquals(SoundEvent.Importance.Low, SoundEvent.Importance.entries.sorted().first())
    }

    @Test
    fun `turning off 'Phone ringing' silences it under both of its labels, and a faint-sounds choice covers both too`() {
        var preferences = SoundAlertPreferences().withMuted("telephone_bell_ringing", true)
        assertTrue(preferences.isMuted("ringtone"))
        val policy = SoundEventPolicy()
        policy.preferences = preferences
        assertNull(policy.evaluate(reading("ringtone")))
        assertNull(policy.evaluate(reading("telephone_bell_ringing", at = 200.0)))
        assertNotNull(policy.evaluate(reading("door_bell", at = 300.0)))

        preferences = preferences.withMuted("ringtone", false)
        assertFalse(preferences.isMuted("telephone_bell_ringing"))
        assertTrue(SoundAlertPreferences(mutedIdentifiers = setOf("whistling")).isMuted("boiling"))

        val faint = SoundAlertPreferences().withSensitive("telephone_bell_ringing", true)
        assertTrue(faint.isSensitive("ringtone"))
        val faintPolicy = SoundEventPolicy()
        faintPolicy.preferences = faint
        assertNotNull(faintPolicy.evaluate(reading("ringtone", confidence = 0.45)))
    }

    @Test
    fun `a siren heard for a minute keeps its banner up the whole time, an ordinary sound's banner is short`() {
        val policy = SoundEventPolicy()
        val alerts = mutableListOf<SoundAlert>()
        for (second in 0 until 60) {
            for (half in listOf(0.0, 0.5)) {
                policy.evaluate(reading("civil_defense_siren", at = 100.0 + second + half))?.let { alerts.add(it) }
            }
        }
        assertTrue(alerts.size >= 3)
        for ((shown, next) in alerts.zip(alerts.drop(1))) {
            assertTrue(next.timestamp - shown.timestamp < shown.bannerSeconds - 2)
        }
        val fresh = SoundEventPolicy()
        val bell = assertNotNull(fresh.evaluate(reading("door_bell")))
        assertEquals(8.0, bell.bannerSeconds)
    }

    @Test
    fun `Settings lists each sound once, so no section shows the same name twice in any language`() {
        val listed = SoundEventCatalog.listed
        assertEquals(listed.size, listed.map { it.cooldownKey }.toSet().size)
        assertEquals(SoundEventCatalog.events.map { it.cooldownKey }.toSet(), listed.map { it.cooldownKey }.toSet())
        for (language in UILanguage.entries) {
            Localization.withLanguage(language) {
                for (importance in SoundEvent.Importance.entries) {
                    val names = SoundEventCatalog.listed.filter { it.importance == importance }.map { it.name }
                    assertEquals(names.size, names.toSet().size, "$language $importance: $names")
                }
            }
        }
    }

    @Test
    fun `two labels for one sound share a cooldown in English too, where their names differ`() {
        Localization.withLanguage(UILanguage.English) {
            for ((first, second) in listOf(
                "telephone_bell_ringing" to "ringtone",
                "boiling" to "whistling",
                "shout" to "yell",
            )) {
                val policy = SoundEventPolicy(persistenceWindowSeconds = 0.0)
                assertNotNull(policy.evaluate(reading(first, at = 100.0)))
                assertNull(policy.evaluate(reading(second, at = 105.0)))
                assertNotNull(policy.evaluate(reading(second, at = 125.0)))
            }
        }
    }

    @Test
    fun `a kettle heard as boiling, then whistling, confirms itself`() {
        val policy = SoundEventPolicy()
        assertNull(policy.evaluate(reading("boiling", confidence = 0.7, at = 100.0)))
        assertNotNull(policy.evaluate(reading("whistling", confidence = 0.7, at = 101.0)))
    }

    @Test
    fun `one window that names the kettle twice is still one window`() {
        val policy = SoundEventPolicy()
        assertNull(policy.evaluate(reading("boiling", confidence = 0.7, at = 100.0)))
        assertNull(policy.evaluate(reading("whistling", confidence = 0.7, at = 100.0)))
        assertNotNull(policy.evaluate(reading("boiling", confidence = 0.7, at = 101.0)))
    }

    @Test
    fun `a confident, listed, important sound becomes an alert`() {
        val policy = SoundEventPolicy()
        val alert = policy.evaluate(reading("door_bell"))
        assertEquals("door_bell", alert?.event?.identifier)
        assertEquals(0.9, alert?.confidence)
        assertEquals(100.0, alert?.timestamp)
    }

    @Test
    fun `low confidence, unknown labels and disabled preferences produce nothing`() {
        val policy = SoundEventPolicy()
        assertNull(policy.evaluate(reading("door_bell", confidence = 0.3)))
        assertNull(policy.evaluate(reading("speech")))

        val disabled = SoundEventPolicy(preferences = SoundAlertPreferences(isEnabled = false))
        assertNull(disabled.evaluate(reading("smoke_detector")))
    }

    @Test
    fun `the importance floor and the mute list are respected`() {
        val policy = SoundEventPolicy(
            preferences = SoundAlertPreferences(
                minimumImportance = SoundEvent.Importance.High,
                mutedIdentifiers = setOf("door_bell"),
            ),
        )
        assertNull(policy.evaluate(reading("cough")))
        assertNull(policy.evaluate(reading("door_bell")))
        assertEquals("knock", policy.evaluate(reading("knock"))?.event?.identifier)
    }

    @Test
    fun `a continuous sound needs a second confirming window before alerting, but an impulsive one alerts on the first`() {
        val policy = SoundEventPolicy()
        val firstWindow = policy.evaluate(reading("civil_defense_siren", confidence = 0.65, at = 100.0))
        val confirmingWindow = policy.evaluate(reading("civil_defense_siren", confidence = 0.65, at = 100.75))
        assertNull(firstWindow)
        assertEquals("civil_defense_siren", confirmingWindow?.event?.identifier)

        val doorbell = policy.evaluate(reading("door_bell", confidence = 0.65, at = 200.0))
        assertEquals("door_bell", doorbell?.event?.identifier)
    }

    @Test
    fun `a single window that's confident enough alerts immediately even for a continuous sound`() {
        val policy = SoundEventPolicy()
        val alert = policy.evaluate(reading("civil_defense_siren", confidence = 0.9, at = 100.0))
        assertEquals("civil_defense_siren", alert?.event?.identifier)
    }

    @Test
    fun `a smoke or fire alarm's own beep, with silent gaps too long for a persistence window, is never held back`() {
        val policy = SoundEventPolicy()
        val alert = policy.evaluate(reading("smoke_detector", confidence = 0.65, at = 100.0))
        assertEquals("smoke_detector", alert?.event?.identifier)
    }

    @Test
    fun `a lone spike from the TV or kitchen clatter that's never confirmed never alerts`() {
        val policy = SoundEventPolicy()
        val spike = policy.evaluate(reading("boiling", confidence = 0.65, at = 100.0))
        val unrelatedLater = policy.evaluate(reading("boiling", confidence = 0.65, at = 105.0))
        assertNull(spike)
        // Arrived after the persistence window, so it starts a fresh,
        // unconfirmed pending window rather than confirming the first.
        assertNull(unrelatedLater)
    }

    @Test
    fun `the same sound is not re-alerted within the cooldown, but a different sound is`() {
        val policy = SoundEventPolicy(cooldownSeconds = 20.0)
        val first = policy.evaluate(reading("dog_bark", at = 100.0))
        val repeatSoon = policy.evaluate(reading("dog_bark", at = 110.0))
        val other = policy.evaluate(reading("door_bell", at = 111.0))
        val afterCooldown = policy.evaluate(reading("dog_bark", at = 121.0))
        assertNotNull(first)
        assertNull(repeatSoon)
        assertNotNull(other)
        assertNotNull(afterCooldown)

        policy.resetCooldowns()
        val afterReset = policy.evaluate(reading("dog_bark", at = 122.0))
        assertNotNull(afterReset)
    }

    @Test
    fun `a clock set back an hour doesn't hold back a new siren`() {
        val policy = SoundEventPolicy(cooldownSeconds = 20.0)
        val before = policy.evaluate(reading("civil_defense_siren", at = 10_000.0))
        val afterTheClockWentBack = policy.evaluate(reading("civil_defense_siren", at = 10_000.0 - 3_600 + 30))
        assertNotNull(before)
        assertNotNull(afterTheClockWentBack)
    }

    @Test
    fun `two catalog entries shown as the same sound share one cooldown, not two`() {
        val policy = SoundEventPolicy(cooldownSeconds = 20.0)
        val ringing = policy.evaluate(reading("telephone_bell_ringing", at = 100.0))
        val ringtone = policy.evaluate(reading("ringtone", at = 100.0))
        assertNotNull(ringing)
        assertNull(ringtone)

        val shout = policy.evaluate(reading("shout", at = 200.0))
        val yell = policy.evaluate(reading("yell", at = 205.0))
        assertNotNull(shout)
        assertNull(yell)

        val boiling = policy.evaluate(reading("boiling", at = 300.0))
        val whistling = policy.evaluate(reading("whistling", at = 305.0))
        assertNotNull(boiling)
        assertNull(whistling)
    }

    @Test
    fun `a whistling kettle is a real classifier label, distinct from boiling, and both are important enough not to be silenced by default`() {
        assertEquals(SoundEvent.Importance.High, SoundEventCatalog.event("whistling")?.importance)
        assertEquals(SoundEvent.Importance.High, SoundEventCatalog.event("boiling")?.importance)

        val policy = SoundEventPolicy(preferences = SoundAlertPreferences(minimumImportance = SoundEvent.Importance.High))
        assertEquals("whistling", policy.evaluate(reading("whistling", at = 1.0))?.event?.identifier)
        assertEquals("boiling", policy.evaluate(reading("boiling", at = 100.0))?.event?.identifier)
    }

    @Test
    fun `a banner gives way only to an alert at least as important`() {
        val smoke = alert("smoke_detector")
        val horn = alert("car_horn")
        assertTrue(smoke.takesBanner(null))
        assertFalse(horn.takesBanner(smoke))
        assertTrue(smoke.takesBanner(horn))
        assertTrue(alert("siren").takesBanner(smoke))
    }

    @Test
    fun `catalog names are in English when the app is`() {
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Doorbell", SoundEventCatalog.event("door_bell")?.name)
            assertEquals("Air raid siren", SoundEventCatalog.event("civil_defense_siren")?.name)
            val identifiers = SoundEventCatalog.events.map { it.identifier }
            assertEquals(identifiers.size, identifiers.toSet().size)
        }
    }

    @Test
    fun `preferences decode tolerantly and round-trip`() {
        assertEquals(SoundAlertPreferences.default, SoundAlertPreferences.fromJson("{}"))

        val custom = SoundAlertPreferences(
            isEnabled = false,
            minimumImportance = SoundEvent.Importance.Critical,
            mutedIdentifiers = setOf("cat", "music"),
            sensitiveIdentifiers = setOf("door_bell"),
        )
        assertEquals(custom, SoundAlertPreferences.fromJson(custom.toJson()))
    }

    @Test
    fun `a settings file saved before sensitivity existed decodes to no sensitive sounds`() {
        val decoded = SoundAlertPreferences.fromJson("""{"isEnabled":true,"minimumImportance":1,"mutedIdentifiers":["cat"]}""")
        assertTrue(decoded.sensitiveIdentifiers.isEmpty())
        assertEquals(setOf("cat"), decoded.mutedIdentifiers)
    }

    @Test
    fun `a sensitive sound alerts at the lower floor, an ordinary one still needs the usual confidence`() {
        val policy = SoundEventPolicy(preferences = SoundAlertPreferences(sensitiveIdentifiers = setOf("door_bell")))
        assertEquals(policy.sensitiveConfidence, policy.requiredConfidence("door_bell"))
        assertEquals(policy.minimumConfidence, policy.requiredConfidence("knock"))

        val faintDoorbell = policy.evaluate(reading("door_bell", confidence = 0.45))
        val faintKnock = policy.evaluate(reading("knock", confidence = 0.45))
        assertEquals("door_bell", faintDoorbell?.event?.identifier)
        assertNull(faintKnock)
    }

    @Test
    fun `sensitivity never raises the floor above the ordinary minimum`() {
        val policy = SoundEventPolicy(
            preferences = SoundAlertPreferences(sensitiveIdentifiers = setOf("door_bell")),
            minimumConfidence = 0.3,
            sensitiveConfidence = 0.4,
        )
        assertEquals(0.3, policy.requiredConfidence("door_bell"))
    }
}
