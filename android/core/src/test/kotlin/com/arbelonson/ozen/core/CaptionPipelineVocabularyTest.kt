package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private fun token(id: UUID, text: String, final: Boolean = false, at: Double = 1_000.0) =
    TranscriptToken(utteranceID = id, text = text, isFinal = final, timestamp = at)

private val whisper = TranscriptionEngineKind.WhisperKit

class CaptionPipelineVocabularyTest {
    @Test
    fun `the engine receives the cleaned vocabulary before streaming starts`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        val settings = AppSettings.default
        settings.vocabulary = listOf(" אבי ", "רותי", "אבי", "")
        pipeline.start(settings)
        assertTrue(pipeline.phase.isListening)
        assertEquals(listOf(listOf("אבי", "רותי")), engine.vocabularySeen)
    }

    @Test
    fun `editing the vocabulary while listening reaches the engine without a restart`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, log) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val callsAfterStart = log.calls
        pipeline.setVocabulary(listOf("סבתא", "דני"))
        assertEquals(listOf(emptyList(), listOf("סבתא", "דני")), engine.vocabularySeen)
        assertTrue(pipeline.phase.isListening)
        assertEquals(callsAfterStart, log.calls)
        assertEquals(listOf("סבתא", "דני"), pipeline.activeSettings?.vocabulary)
    }

    @Test
    fun `a vocabulary edit while stopped is remembered for the next start, not sent to a dead engine`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.setVocabulary(listOf("דני"))
        assertTrue(engine.vocabularySeen.isEmpty())
        pipeline.start(AppSettings.default)
        assertEquals(listOf(emptyList()), engine.vocabularySeen)
    }

    @Test
    fun `enabled keyword alerts are primed into the engine's vocabulary at start`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        val settings = AppSettings.default
        settings.vocabulary = listOf("רותי")
        settings.keywordAlerts = listOf(KeywordAlert(phrase = "סבתא"), KeywordAlert(phrase = "אמבולנס", isEnabled = false))
        pipeline.start(settings)
        assertEquals(listOf(listOf("רותי", "סבתא")), engine.vocabularySeen)
    }

    @Test
    fun `a keyword alert phrase already in the vocabulary is not primed twice`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        val settings = AppSettings.default
        settings.vocabulary = listOf("סבתא")
        settings.keywordAlerts = listOf(KeywordAlert(phrase = "סבתא"))
        pipeline.start(settings)
        assertEquals(listOf(listOf("סבתא")), engine.vocabularySeen)
    }

    @Test
    fun `changing the keyword alert list while listening re-primes the engine without a restart`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, log) = makePipeline(engines = mapOf(whisper to engine))
        val settings = AppSettings.default
        settings.vocabulary = listOf("רותי")
        pipeline.start(settings)
        val callsAfterStart = log.calls
        pipeline.setKeywordAlerts(listOf(KeywordAlert(phrase = "סבתא")))
        assertTrue(eventually { engine.vocabularySeen.size == 2 })
        assertEquals(listOf(listOf("רותי"), listOf("רותי", "סבתא")), engine.vocabularySeen)
        assertTrue(pipeline.phase.isListening)
        assertEquals(callsAfterStart, log.calls)
    }
}

class CaptionPipelinePermissionTest {
    @Test
    fun `asking for the microphone up front does not start anything`() = runTest {
        val audio = FakeAudioCapturer()
        audio.permissionAnswer = AudioPermission.Denied
        val (pipeline, _, log) = makePipeline(audio = audio)
        val answer = pipeline.requestMicrophonePermission()
        assertEquals(AudioPermission.Denied, answer)
        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertEquals(0, log.calls)
        assertEquals(listOf("requestPermission"), audio.calls)
    }
}

class CaptionPipelineSpeakerNameTest {
    @Test
    fun `renaming and forgetting a saved speaker updates the lines already on screen`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.enroll(SpeakerProfile(name = "אבי", embedding = floatArrayOf(1f, 0f, 0f)))
        pipeline.start(AppSettings.default)

        // The line must exist before its audio window is embedded, and a
        // positive-led window is FakeEmbedder's [1, 0, 0] voice.
        val id = UUID.randomUUID()
        engine.emit(token(id, "שלום", at = 1_000.0))
        assertTrue(eventually { pipeline.segments.size == 1 })
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.segments.firstOrNull()?.speakerClusterID != null })
        val segment = pipeline.segments[0]
        assertEquals("אבי", pipeline.displayName(segment))

        pipeline.renameSpeakers("אבי", "אביגדור")
        assertEquals("אביגדור", pipeline.displayName(segment))

        pipeline.forgetSpeakerName("אביגדור")
        assertTrue(pipeline.displayName(segment).startsWith("דובר "))
    }
}

class CaptionPipelineAlertHookTest {
    @Test
    fun `a keyword heard in a line calls the hook once with the line`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        val settings = AppSettings.default
        settings.keywordAlerts = listOf(KeywordAlert(phrase = "סבתא"))
        val calls = ArrayList<Pair<Int, String>>()
        pipeline.onKeywordHits = { hits, segment -> calls.add(hits.size to segment.text) }
        pipeline.start(settings)

        val id = UUID.randomUUID()
        engine.emit(token(id, "סבתא בואי"))
        engine.emit(token(id, "סבתא בואי לאכול", final = true))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })
        assertEquals(1, calls.size)
        assertEquals(1, calls.firstOrNull()?.first)
        assertEquals("סבתא בואי", calls.firstOrNull()?.second)
    }

    @Test
    fun `a sound alert calls the hook`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, _, _) = makePipeline(soundDetector = detector)
        val raised = ArrayList<String>()
        pipeline.onSoundAlert = { raised.add(it.event.identifier) }
        pipeline.start(AppSettings.default)
        detector.push(SoundObservation("door_bell", 0.95, 1_000.0))
        assertTrue(eventually { raised == listOf("door_bell") })
    }

    @Test
    fun `a smoke alarm also scored as an alarm clock is one notification, not a second one naming the clock`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, _, _) = makePipeline(soundDetector = detector)
        val raised = ArrayList<String>()
        pipeline.onSoundAlert = { raised.add(it.event.identifier) }
        pipeline.start(AppSettings.default)
        detector.push(SoundObservation("smoke_detector", 0.95, 1_000.0))
        detector.push(SoundObservation("alarm_clock", 0.7, 1_000.0))
        assertTrue(eventually { pipeline.soundAlerts.size == 2 })
        assertEquals(listOf("smoke_detector"), raised)

        detector.push(SoundObservation("door_bell", 0.9, 1_030.0))
        assertTrue(eventually { raised == listOf("smoke_detector", "door_bell") })
    }
}

class CaptionPipelineSilenceSpeakerTest {
    @Test
    fun `silence and faint noise never open a speaker or label a line`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()
        engine.emit(token(id, "..."))
        assertTrue(eventually { pipeline.segments.size == 1 })

        audio.push(FloatArray(24_000))
        // About -70 dBFS: a quiet room, below the -60 dBFS speech threshold.
        audio.push(FloatArray(24_000) { 0.0003f })
        assertTrue(eventually { pipeline.stats.audioSecondsReceived == 3.0 })
        assertTrue(pipeline.speakerClusters.isEmpty())
        assertNull(pipeline.segments.firstOrNull()?.speakerClusterID)
    }

    @Test
    fun `a short reply spoken before its line appears still gets that voice`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)

        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.speakerClusters.size == 1 })
        val voice = pipeline.speakerClusters[0].id

        val id = UUID.randomUUID()
        engine.emit(token(id, "כן", final = true))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertEquals(voice, pipeline.segments.firstOrNull()?.speakerClusterID)
    }

    @Test
    fun `a short reply after someone else's line does not take that person's voice`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val question = UUID.randomUUID()
        engine.emit(token(question, "תה?"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.segments.firstOrNull()?.speakerClusterID != null })
        engine.emit(token(question, "תה?", final = true))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })

        engine.emit(token(UUID.randomUUID(), "כן", final = true))
        assertTrue(eventually { pipeline.segments.size == 2 })
        assertNull(pipeline.segments.lastOrNull()?.speakerClusterID)
    }

    @Test
    fun `a second speaker split out of the same audio doesn't take the voice heard just before`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)

        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.speakerClusters.size == 1 })
        val voice = pipeline.speakerClusters[0].id

        engine.emit(token(UUID.randomUUID(), "מה שלומך?", final = true))
        val reply = token(UUID.randomUUID(), "טוב, תודה", final = true).copy(startsNewSpeakerTurn = true)
        engine.emit(reply)
        assertTrue(eventually { pipeline.segments.size == 2 })
        assertEquals(voice, pipeline.segments.firstOrNull()?.speakerClusterID)
        assertNull(pipeline.segments.lastOrNull()?.speakerClusterID)
    }

    @Test
    fun `an old voice is not pinned on a line that starts much later`() = runTest {
        val clock = TestClock()
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine), now = { clock.now })
        pipeline.start(AppSettings.default)

        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.speakerClusters.size == 1 })
        clock.advance(10.0)

        engine.emit(token(UUID.randomUUID(), "שלום", at = clock.now))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertNull(pipeline.segments.firstOrNull()?.speakerClusterID)
    }
}

class CaptionPipelineSoundStatusTest {
    @Test
    fun `the stats say when sound detection is running, and when the classifier stops on its own`() = runTest {
        val detector = FakeSoundDetector()
        val (pipeline, _, _) = makePipeline(soundDetector = detector)
        assertFalse(pipeline.stats.soundDetectionRunning)
        pipeline.start(AppSettings.default)
        assertTrue(pipeline.stats.soundDetectionRunning)

        detector.finish()
        assertTrue(eventually { !pipeline.stats.soundDetectionRunning })
        assertTrue(pipeline.phase.isListening)
    }

    @Test
    fun `stopping captions marks sound detection as not running`() = runTest {
        val (pipeline, _, _) = makePipeline(soundDetector = FakeSoundDetector())
        pipeline.start(AppSettings.default)
        pipeline.stop()
        assertFalse(pipeline.stats.soundDetectionRunning)
    }
}
