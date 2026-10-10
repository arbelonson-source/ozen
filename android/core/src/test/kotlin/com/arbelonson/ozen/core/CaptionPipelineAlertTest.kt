package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

private fun token(id: UUID, text: String, final: Boolean = false, at: Double = 1_000.0) =
    TranscriptToken(utteranceID = id, text = text, isFinal = final, timestamp = at)

private val whisper = TranscriptionEngineKind.WhisperKit

private fun settingsWithAlert(phrase: String): AppSettings {
    val settings = AppSettings.default
    settings.keywordAlerts = listOf(KeywordAlert(phrase = phrase))
    return settings
}

private fun chunk() = FloatArray(1_024) { 0.1f }

class CaptionPipelineAlertTest {
    @Test
    fun `a keyword in a caption fires once per utterance and marks the segment`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(settingsWithAlert("סבתא"))
        val id = UUID.randomUUID()

        engine.emit(token(id, "היום"))
        engine.emit(token(id, "היום לסבתא"))
        engine.emit(token(id, "היום לסבתא יש"))
        engine.emit(token(id, "היום לסבתא יש אורחים", final = true))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })

        assertEquals(1, pipeline.keywordHits.size)
        assertEquals("לסבתא", pipeline.keywordHits.firstOrNull()?.match?.matchedText)
        assertEquals(id, pipeline.keywordHits.firstOrNull()?.segmentID)
        assertEquals(setOf(id), pipeline.keywordHitSegmentIDs)

        val id2 = UUID.randomUUID()
        engine.emit(token(id2, "סבתא שוב"))
        assertTrue(eventually { pipeline.keywordHits.size == 2 })
    }

    @Test
    fun `an evening of name alerts keeps the newest 50, in order`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(settingsWithAlert("סבתא"))

        val ids = (0..50).map { UUID.randomUUID() }
        for ((number, id) in ids.withIndex()) {
            engine.emit(token(id, "סבתא $number", final = true))
        }
        assertTrue(eventually { pipeline.keywordHits.lastOrNull()?.segmentID == ids.last() })
        assertEquals(ids.drop(1), pipeline.keywordHits.map { it.segmentID })
    }

    @Test
    fun `a word the finished text takes back no longer marks the line`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(settingsWithAlert("סבתא"))
        val id = UUID.randomUUID()

        engine.emit(token(id, "היום סבתא"))
        assertTrue(eventually { pipeline.keywordHitSegmentIDs == setOf(id) })
        engine.emit(token(id, "היום שבת אורחים", final = true))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })

        assertTrue(pipeline.keywordHitSegmentIDs.isEmpty())
    }

    @Test
    fun `a live guess cut off half way through a word sets off no alert, the finished line decides`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(settingsWithAlert("טל"))
        val phone = UUID.randomUUID()

        engine.emit(token(phone, "כדי שכשנלחץ על הטל..."))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.text == "כדי שכשנלחץ על הטל..." })
        assertTrue(pipeline.keywordHits.isEmpty())
        assertTrue(pipeline.keywordHitSegmentIDs.isEmpty())
        engine.emit(token(phone, "כדי שכשנלחץ על הטלפון, אז הוא זז.", final = true))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })
        assertTrue(pipeline.keywordHits.isEmpty())

        // Her name as the last word of a line still calls her once it's finished.
        val call = UUID.randomUUID()
        engine.emit(token(call, "בוא הנה טל..."))
        assertTrue(eventually { pipeline.segments.lastOrNull()?.text == "בוא הנה טל..." })
        assertTrue(pipeline.keywordHits.isEmpty())
        engine.emit(token(call, "בוא הנה טל...", final = true))
        assertTrue(eventually { pipeline.keywordHits.size == 1 })
        assertEquals(call, pipeline.keywordHits.firstOrNull()?.segmentID)
    }

    @Test
    fun `a word a live guess dropped and the finished text brought back marks the line again, without a second alert`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(settingsWithAlert("סבתא"))
        val id = UUID.randomUUID()

        engine.emit(token(id, "שלום סבתא"))
        assertTrue(eventually { pipeline.keywordHitSegmentIDs == setOf(id) })
        engine.emit(token(id, "שלום סבתה"))
        assertTrue(eventually { pipeline.keywordHitSegmentIDs.isEmpty() })
        engine.emit(token(id, "שלום סבתא", final = true))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })

        assertEquals(setOf(id), pipeline.keywordHitSegmentIDs)
        assertEquals(1, pipeline.keywordHits.size)
    }

    @Test
    fun `changing the keyword list takes effect without a restart, and clearing the transcript clears hits`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        engine.emit(token(UUID.randomUUID(), "דנה הגיעה"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertTrue(pipeline.keywordHits.isEmpty())

        pipeline.setKeywordAlerts(listOf(KeywordAlert(phrase = "דנה")))
        engine.emit(token(UUID.randomUUID(), "ודנה יצאה"))
        assertTrue(eventually { pipeline.keywordHits.size == 1 })
        assertEquals(1, audio.calls.count { it == "startCapture" })

        pipeline.clearTranscript()
        assertTrue(pipeline.keywordHits.isEmpty())
        assertTrue(pipeline.keywordHitSegmentIDs.isEmpty())
    }

    @Test
    fun `an alert whose word is changed while a sentence is still coming fires on the new word in that sentence`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val alert = KeywordAlert(phrase = "דנה")
        pipeline.setKeywordAlerts(listOf(alert))
        val sentence = UUID.randomUUID()
        engine.emit(token(sentence, "דנה הגיעה"))
        assertTrue(eventually { pipeline.keywordHits.size == 1 })

        val edited = alert.copy(phrase = "רותי")
        pipeline.setKeywordAlerts(listOf(edited))
        engine.emit(token(sentence, "דנה הגיעה עם רותי"))
        assertTrue(eventually { pipeline.keywordHits.size == 2 })
    }

    @Test
    fun `after the screen is cleared mid-sentence, the word said again in that sentence alerts again`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        pipeline.setKeywordAlerts(listOf(KeywordAlert(phrase = "דנה")))
        val sentence = UUID.randomUUID()
        engine.emit(token(sentence, "דנה הגיעה"))
        assertTrue(eventually { pipeline.keywordHits.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(sentence, "דנה הגיעה ודנה יצאה"))
        assertTrue(eventually { pipeline.keywordHits.size == 1 })
    }

    @Test
    fun `a keyword added while listening still alerts after captions start again on their own`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        pipeline.setKeywordAlerts(listOf(KeywordAlert(phrase = "דנה")))

        pipeline.pause()
        pipeline.resume()
        assertTrue(eventually { pipeline.phase.isListening })
        engine.emit(token(UUID.randomUUID(), "דנה הגיעה"))
        assertTrue(eventually { pipeline.keywordHits.size == 1 })
    }

    @Test
    fun `sound observations become alerts through the policy, and audio reaches the detector`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)

        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        detector.push(SoundObservation("door_bell", 0.9, 100.0))
        assertTrue(eventually { pipeline.soundAlerts.size == 1 })
        assertEquals("פעמון דלת", pipeline.soundAlerts.firstOrNull()?.event?.name)

        // Same sound inside the cooldown: no second banner.
        detector.push(SoundObservation("door_bell", 0.95, 105.0))
        detector.push(SoundObservation("speech", 0.99, 106.0))
        detector.push(SoundObservation("cough", 0.2, 107.0))
        detector.push(SoundObservation("smoke_detector", 0.8, 108.0))
        assertTrue(eventually { pipeline.soundAlerts.size == 2 })
        assertEquals("smoke_detector", pipeline.soundAlerts.lastOrNull()?.event?.identifier)

        pipeline.dismissSoundAlert(pipeline.soundAlerts[0].id)
        assertEquals(1, pipeline.soundAlerts.size)
        pipeline.clearSoundAlerts()
        assertTrue(pipeline.soundAlerts.isEmpty())
    }

    @Test
    fun `the screen's alert is the strongest of one reading, and a dismissed banner doesn't bring back the one before`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        detector.push(SoundObservation("door_bell", 0.9, 100.0))
        detector.push(SoundObservation("knock", 0.9, 150.0))
        assertTrue(eventually { pipeline.soundAlerts.size == 2 })
        val knock = pipeline.soundAlerts[1]
        pipeline.dismissSoundAlert(knock.id)
        assertEquals(knock.id, pipeline.screenSoundAlert?.id)

        // One reading of a smoke alarm that also scores as an alarm clock.
        detector.push(SoundObservation("smoke_detector", 0.95, 200.0))
        detector.push(SoundObservation("alarm_clock", 0.7, 200.0))
        assertTrue(eventually { pipeline.soundAlerts.size == 3 })
        assertEquals("smoke_detector", pipeline.screenSoundAlert?.event?.identifier)

        // A weaker sound heard later is news again.
        detector.push(SoundObservation("door_bell", 0.9, 230.0))
        assertTrue(eventually { pipeline.soundAlerts.size == 4 })
        assertEquals("door_bell", pipeline.screenSoundAlert?.event?.identifier)
    }

    @Test
    fun `two equally urgent sounds in one reading, the surer one stays on screen and only it is announced`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        val announced = ArrayList<String>()
        pipeline.onSoundAlert = { announced.add(it.event.identifier) }
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        // An air raid siren the classifier also scores, less surely, as a
        // police siren: the banner must not end up naming the police.
        detector.push(SoundObservation("civil_defense_siren", 0.95, 300.0))
        detector.push(SoundObservation("police_siren", 0.88, 300.0))
        assertTrue(eventually { pipeline.soundAlerts.size == 2 })
        assertEquals("civil_defense_siren", pipeline.screenSoundAlert?.event?.identifier)
        assertEquals(listOf("civil_defense_siren"), announced)
    }

    @Test
    fun `a banner shown again after a covering screen closes gets only the rest of its time`() = runTest {
        val clock = TestClock()
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector, now = { clock.now })
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        detector.push(SoundObservation("civil_defense_siren", 0.95, clock.now))
        assertTrue(eventually { pipeline.screenSoundAlert != null })
        val siren = assertNotNull(pipeline.screenSoundAlert)
        clock.advance(3.0)
        assertTrue(abs(pipeline.bannerSecondsLeft(siren) - (siren.bannerSeconds - 3)) < 0.01)
        clock.advance(60.0)
        assertEquals(0.0, pipeline.bannerSecondsLeft(siren))
    }

    @Test
    fun `a line keeps the scale of the model that wrote it, switching to the noise-trained model doesn't re-judge the lines before`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        val a3 = "ozen-turbo-hebrew-a3-8bit"
        val line = "נפגשים מחר בבוקר אצל הרופא"
        val ivrit = AppSettings.default
        ivrit.whisperModelVariant = "ivrit-large-v3-turbo-8bit"
        val noiseTrained = ivrit.copy()
        noiseTrained.whisperModelVariant = a3

        pipeline.start(ivrit)
        engine.emit(TranscriptToken(utteranceID = UUID.randomUUID(), text = line, isFinal = true, timestamp = 100.0, confidence = 0.82f))
        assertTrue(eventually { pipeline.segments.size == 1 })
        pipeline.restart(noiseTrained)
        assertTrue(eventually { pipeline.phase.isListening })
        engine.emit(TranscriptToken(utteranceID = UUID.randomUUID(), text = line, isFinal = true, timestamp = 110.0, confidence = 0.82f))
        assertTrue(eventually { pipeline.segments.size == 2 })

        assertFalse(CaptionConfidence.isUncertain(pipeline.segments[0], engine = whisper, model = a3))
        assertTrue(CaptionConfidence.isUncertain(pipeline.segments[1], engine = whisper, model = a3))
        assertEquals(
            listOf(
                CaptionConfidence.Scorer(whisper, "ivrit-large-v3-turbo-8bit"),
                CaptionConfidence.Scorer(whisper, a3),
            ),
            pipeline.segments.map { it.scoredBy },
        )
    }

    @Test
    fun `a lesser sound heard during a siren's banner still buzzes and is read out, but the siren keeps the banner and the rest of its time`() = runTest {
        val clock = TestClock()
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector, now = { clock.now })
        val announced = ArrayList<String>()
        pipeline.onSoundAlert = { announced.add(it.event.identifier) }
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        detector.push(SoundObservation("civil_defense_siren", 0.95, clock.now))
        assertTrue(eventually { pipeline.screenSoundAlert != null })
        val siren = assertNotNull(pipeline.screenSoundAlert)
        clock.advance(5.0)
        detector.push(SoundObservation("door_bell", 0.95, clock.now))
        assertTrue(eventually { pipeline.soundAlerts.size == 2 })

        assertEquals(listOf("civil_defense_siren", "door_bell"), announced)
        assertEquals("door_bell", pipeline.screenSoundAlert?.event?.identifier)
        assertTrue(abs(pipeline.bannerSecondsLeft(siren) - (siren.bannerSeconds - 5)) < 0.01)
        assertEquals(siren.id, pipeline.currentBannerSoundAlert?.id)
        clock.advance(siren.bannerSeconds)
        assertNull(pipeline.currentBannerSoundAlert)
    }

    @Test
    fun `a siren banner tapped away leaves the banner to the next sound, heard before or after the tap`() = runTest {
        for (doorbellFirst in listOf(false, true)) {
            val clock = TestClock()
            val detector = FakeSoundDetector()
            val (pipeline, audio, _) = makePipeline(soundDetector = detector, now = { clock.now })
            pipeline.start(AppSettings.default)
            audio.push(chunk())
            assertTrue(eventually { detector.chunksSeen == 1 })

            detector.push(SoundObservation("civil_defense_siren", 0.95, clock.now))
            assertTrue(eventually { pipeline.screenSoundAlert != null })
            val siren = assertNotNull(pipeline.screenSoundAlert)
            if (!doorbellFirst) pipeline.dismissSoundAlert(siren.id)
            clock.advance(2.0)
            detector.push(SoundObservation("door_bell", 0.95, clock.now))
            assertTrue(eventually { pipeline.screenSoundAlert?.event?.identifier == "door_bell" })
            val doorbell = assertNotNull(pipeline.screenSoundAlert)
            if (doorbellFirst) pipeline.dismissSoundAlert(siren.id)
            clock.advance(1.0)

            assertEquals(doorbell.id, pipeline.currentBannerSoundAlert?.id, "doorbell first: $doorbellFirst")
            assertTrue(abs(pipeline.bannerSecondsLeft(doorbell) - (doorbell.bannerSeconds - 1)) < 0.01)
        }
    }

    @Test
    fun `an alert past its banner time, like one heard while the app was away, is no longer the current one to buzz, flash or read out`() = runTest {
        val clock = TestClock()
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector, now = { clock.now })
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        detector.push(SoundObservation("door_bell", 0.9, clock.now))
        assertTrue(eventually { pipeline.screenSoundAlert != null })
        val bell = assertNotNull(pipeline.screenSoundAlert)
        assertEquals(bell.id, pipeline.currentScreenSoundAlert?.id)
        clock.advance(bell.bannerSeconds - 1)
        assertEquals(bell.id, pipeline.currentScreenSoundAlert?.id)
        clock.advance(2.0)
        assertEquals(bell.id, pipeline.screenSoundAlert?.id)
        assertNull(pipeline.currentScreenSoundAlert)
    }

    @Test
    fun `a clock set back after an alert never makes its banner last longer than its own time`() = runTest {
        val clock = TestClock()
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector, now = { clock.now })
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        detector.push(SoundObservation("civil_defense_siren", 0.95, clock.now))
        assertTrue(eventually { pipeline.screenSoundAlert != null })
        val siren = assertNotNull(pipeline.screenSoundAlert)
        clock.advance(-600.0)
        assertTrue(pipeline.bannerSecondsLeft(siren) <= siren.bannerSeconds)
    }

    @Test
    fun `while the phone vibrates for an alert, what the microphone hears of the buzz is not an alert`() = runTest {
        val clock = TestClock()
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector, now = { clock.now })
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        val vibration = AlertVibration.pattern(SoundEvent.Importance.Critical)
        pipeline.ignoreSounds(vibration)
        detector.push(SoundObservation("telephone_bell_ringing", 0.9, clock.now))
        clock.advance(vibration.totalSeconds + 1)
        detector.push(SoundObservation("alarm_clock", 0.9, clock.now))
        // A real smoke alarm in the same moment is never taken for the buzz.
        detector.push(SoundObservation("smoke_detector", 0.9, clock.now))
        assertTrue(eventually { pipeline.soundAlerts.isNotEmpty() })
        assertEquals(listOf("smoke_detector"), pipeline.soundAlerts.map { it.event.identifier })

        // The ignored ring started no cooldown: a real one right after counts.
        clock.advance(1.0)
        detector.push(SoundObservation("telephone_bell_ringing", 0.9, clock.now))
        assertTrue(eventually { pipeline.soundAlerts.size == 2 })
    }

    @Test
    fun `a clock set back right after the phone vibrated doesn't keep ringing phones and knocks ignored until it catches up`() = runTest {
        val clock = TestClock()
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector, now = { clock.now })
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        pipeline.ignoreSounds(AlertVibration.pattern(SoundEvent.Importance.Critical))
        clock.advance(-3_600.0)
        detector.push(SoundObservation("telephone_bell_ringing", 0.9, clock.now))
        assertTrue(eventually { pipeline.soundAlerts.map { it.event.identifier } == listOf("telephone_bell_ringing") })
    }

    @Test
    fun `a second buzz while the first is still going ignores the late reports of both, not only the second`() = runTest {
        val clock = TestClock()
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector, now = { clock.now })
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        val vibration = AlertVibration.pattern(SoundEvent.Importance.Critical)
        val firstBuzz = clock.now
        pipeline.ignoreSounds(vibration)
        clock.advance(vibration.totalSeconds / 2)
        pipeline.ignoreSounds(vibration)
        // The classifier reports a little late: a reading of the first buzz
        // arrives after the second began, and one of the second after the
        // first one's own window would have closed.
        detector.push(SoundObservation("telephone_bell_ringing", 0.9, firstBuzz + 0.1))
        clock.advance(vibration.totalSeconds + 1)
        detector.push(SoundObservation("knock", 0.9, clock.now))
        detector.push(SoundObservation("smoke_detector", 0.9, clock.now))
        assertTrue(eventually { pipeline.soundAlerts.isNotEmpty() })
        assertEquals(listOf("smoke_detector"), pipeline.soundAlerts.map { it.event.identifier })
    }

    @Test
    fun `an evening of sound alerts keeps the newest 30, in order`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, audio, _) = makePipeline(soundDetector = detector)
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { detector.chunksSeen == 1 })

        val times = (0..30).map { 100 + it.toDouble() * (SoundEventPolicy.DEFAULT_COOLDOWN_SECONDS + 5) }
        for (time in times) {
            detector.push(SoundObservation("door_bell", 0.9, time))
        }
        assertTrue(eventually { pipeline.soundAlerts.lastOrNull()?.timestamp == times.last() })
        assertEquals(times.drop(1), pipeline.soundAlerts.map { it.timestamp })
        assertEquals(times.last(), pipeline.screenSoundAlert?.timestamp)
    }

    @Test
    fun `sound preferences from settings are applied at start`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, _, _) = makePipeline(soundDetector = detector)
        val settings = AppSettings.default
        settings.soundAlerts = SoundAlertPreferences(isEnabled = false)
        pipeline.start(settings)

        detector.push(SoundObservation("door_bell", 0.9, 100.0))
        delay(50.milliseconds)
        assertTrue(pipeline.soundAlerts.isEmpty())

        pipeline.soundPolicy.preferences = pipeline.soundPolicy.preferences.copy(isEnabled = true)
        detector.push(SoundObservation("door_bell", 0.9, 101.0))
        assertTrue(eventually { pipeline.soundAlerts.size == 1 })
    }

    @Test
    fun `without a detector the pipeline still runs with two audio consumers`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        audio.push(chunk())
        assertTrue(eventually { engine.chunksSeen == 1 })
        assertEquals(PipelinePhase.Listening, pipeline.phase)
        assertTrue(pipeline.soundAlerts.isEmpty())
    }
}
