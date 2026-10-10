package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private fun heard(identifier: String, confidence: Double, at: Double) =
    SoundObservation(identifier = identifier, confidence = confidence, timestamp = at)

class SoundNearMissesTest {
    @Test
    fun `keeps the best confidence and the latest time for each sound below the alert level`() {
        val misses = SoundNearMisses()
        misses.record(heard("door_bell", 0.41, 100.0), alertConfidence = 0.6)
        misses.record(heard("door_bell", 0.52, 50.0), alertConfidence = 0.6)
        misses.record(heard("door_bell", 0.35, 200.0), alertConfidence = 0.6)
        misses.record(heard("door_bell", 0.9, 300.0), alertConfidence = 0.6)
        misses.record(heard("speech", 0.5, 310.0), alertConfidence = 0.6)
        misses.record(heard("knock", 0.33, 150.0), alertConfidence = 0.6)
        assertEquals(listOf("door_bell", "knock"), misses.recentFirst.map { it.identifier })
        assertEquals(0.52, misses.recentFirst.first().bestConfidence)
        assertEquals(200.0, misses.recentFirst.first().lastHeardAt)
        assertEquals("door_bell 52% 00:03:20, knock 33% 00:02:30", misses.reportLine(utcOffsetSeconds = 0))
    }

    @Test
    fun `two identifiers the catalog shows as the same sound are merged into one near-miss`() {
        val misses = SoundNearMisses()
        misses.record(heard("telephone_bell_ringing", 0.45, 100.0), alertConfidence = 0.6)
        misses.record(heard("ringtone", 0.50, 101.0), alertConfidence = 0.6)
        assertEquals(1, misses.entries.size)
        assertEquals(0.50, misses.entries.first().bestConfidence)
        assertEquals(101.0, misses.entries.first().lastHeardAt)
    }

    @Test
    fun `merging follows the catalog's pairs whatever the app's language, not the translated names`() {
        for (language in UILanguage.entries) {
            Localization.withLanguage(language) {
                val misses = SoundNearMisses()
                misses.record(heard("boiling", 0.40, 100.0), alertConfidence = 0.6)
                misses.record(heard("whistling", 0.45, 101.0), alertConfidence = 0.6)
                assertEquals(1, misses.entries.size, "$language")
                val kettle = assertNotNull(SoundEventCatalog.event("whistling"))
                assertEquals(0.45, misses.entry(kettle)?.bestConfidence, "$language")

                misses.record(heard("yell", 0.50, 102.0), alertConfidence = 0.6)
                val scream = assertNotNull(SoundEventCatalog.event("screaming"))
                assertNull(misses.entry(scream), "$language")
                assertEquals(2, misses.entries.size, "$language")
            }
        }
    }

    @Test
    fun `looks an entry up by event, merging the same synonym identifiers record() does`() {
        val misses = SoundNearMisses()
        misses.record(heard("ringtone", 0.5, 100.0), alertConfidence = 0.6)
        val phone = assertNotNull(SoundEventCatalog.event("telephone_bell_ringing"))
        assertEquals(0.5, misses.entry(phone)?.bestConfidence)
        val doorbell = assertNotNull(SoundEventCatalog.event("door_bell"))
        assertNull(misses.entry(doorbell))
    }

    @Test
    fun `remembers a limited number of sounds, forgetting the one heard longest ago`() {
        val misses = SoundNearMisses()
        val identifiers = SoundEventCatalog.events.map { it.identifier }.take(SoundNearMisses.limit + 1)
        for ((offset, identifier) in identifiers.withIndex()) {
            misses.record(heard(identifier, 0.4, offset.toDouble()), alertConfidence = 0.6)
        }
        assertEquals(SoundNearMisses.limit, misses.entries.size)
        assertFalse(misses.entries.any { it.identifier == identifiers.first() })
        assertNull(SoundNearMisses().reportLine(utcOffsetSeconds = 0))
    }
}

class PipelineSoundNearMissTest {
    @Test
    fun `a faint doorbell is noted without an alert, a clear one alerts and isn't a near miss`() = runTest {
        val detector = FakeSoundDetector()
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { FakeEngine() },
            embedder = FakeEmbedder(),
            soundDetector = detector,
        )
        pipeline.start(AppSettings.default)
        detector.push(SoundObservation(identifier = "door_bell", confidence = 0.45, timestamp = 1_000.0))
        assertTrue(eventually { pipeline.soundNearMisses.entries.size == 1 })
        assertTrue(pipeline.soundAlerts.isEmpty())

        detector.push(SoundObservation(identifier = "smoke_detector", confidence = 0.95, timestamp = 1_001.0))
        assertTrue(eventually { pipeline.soundAlerts.size == 1 })
        assertEquals(listOf("door_bell"), pipeline.soundNearMisses.entries.map { it.identifier })
    }
}
