package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

private fun token(id: UUID, text: String, final: Boolean = false, at: Double = 1_000.0) =
    TranscriptToken(utteranceID = id, text = text, isFinal = final, timestamp = at)

private val whisper = TranscriptionEngineKind.WhisperKit

class CaptionPipelineTokenTest {
    @Test
    fun `the invisible direction mark the Hebrew model starts some lines with never reaches the saved line`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        engine.emit(token(UUID.randomUUID(), "‫שלום סבתא‬", final = true))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })
        assertEquals("שלום סבתא", pipeline.segments.firstOrNull()?.text)
    }

    @Test
    fun `the last sound heard is kept in memory for a marked problem, and forgotten when captions stop`() = runTest {
        val audio = FakeAudioCapturer()
        val (pipeline, _, _) = makePipeline(audio = audio, engines = mapOf(whisper to FakeEngine()))
        pipeline.start(AppSettings.default)
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
        audio.push(floatArrayOf(0.25f, -0.5f, 0.75f))
        assertTrue(eventually { pipeline.recentAudioSamples.contentEquals(floatArrayOf(0.25f, -0.5f, 0.75f)) })
        pipeline.stop()
        assertTrue(pipeline.recentAudioSamples.isEmpty())
    }

    @Test
    fun `only the last 30 seconds of sound are kept, as the troubleshooting guide tells her`() = runTest {
        val audio = FakeAudioCapturer()
        val (pipeline, _, _) = makePipeline(audio = audio, engines = mapOf(whisper to FakeEngine()))
        pipeline.start(AppSettings.default)
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
        for (second in 0 until 31) {
            audio.push(FloatArray(16_000) { second.toFloat() / 1_000 })
        }
        assertTrue(eventually { pipeline.recentAudioSamples.lastOrNull() == 30f / 1_000 })
        val kept = pipeline.recentAudioSamples
        assertEquals(30 * 16_000, kept.size)
        assertEquals(1f / 1_000, kept.firstOrNull())
        pipeline.stop()
        val root = File(System.getProperty("ozen.fixtures")).parentFile.parentFile
        val guide = File(root, "docs/troubleshooting.md").readText()
        assertTrue(guide.contains("keeps the last ${kept.size / 16_000} seconds of sound"))
    }

    @Test
    fun `the report's noise floor and margin are the voice detector's own, in decibels`() = runTest {
        val audio = FakeAudioCapturer()
        val (pipeline, _, _) = makePipeline(audio = audio, engines = mapOf(whisper to FakeEngine()))
        pipeline.start(AppSettings.default)
        assertTrue(eventually { pipeline.phase == PipelinePhase.Listening })
        val chunk = FloatArray(688) { 0.003f }
        val detector = EnergyVoiceDetector()
        detector.isSpeech(chunk)
        audio.push(chunk)
        assertTrue(eventually { pipeline.stats.noiseFloorDecibels != null })
        val floor = assertNotNull(pipeline.stats.noiseFloorDecibels)
        val margin = assertNotNull(pipeline.stats.noiseMarginDecibels)
        assertTrue(abs(floor - (20f * log10(detector.noiseFloor)).toDouble()) < 0.01)
        assertTrue(abs(margin - (20f * log10(detector.currentNoiseFloorRatio)).toDouble()) < 0.01)
        // A new session starts at the cautious margin, about 8 dB.
        assertTrue(abs(margin - 8) < 0.1)
        pipeline.stop()
    }

    @Test
    fun `tokens become segments - the same utterance updates in place, final commits it`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()

        engine.emit(token(id, "שלום"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        engine.emit(token(id, "שלום סבתא"))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.text == "שלום סבתא" })
        assertEquals(false, pipeline.segments.firstOrNull()?.isCommitted)

        engine.emit(token(id, "שלום סבתא", final = true))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })
        assertEquals(1, pipeline.segments.size)
        assertEquals(3, pipeline.stats.tokensReceived)
        assertEquals(1, pipeline.stats.segmentsCommitted)
        assertEquals(1, pipeline.committedLineCount)
    }

    @Test
    fun `a segment the engine never finalizes is committed by the stale timer`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine), now = { 1_000.0 })
        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()
        engine.emit(token(id, "מילה", at = 1_000.0))
        assertTrue(eventually { pipeline.segments.size == 1 })

        // Longer than the old 1.2 s threshold and longer than a Whisper
        // final pass takes: the line must still be open.
        pipeline.commitStaleSegments(now = 1_004.5)
        assertEquals(false, pipeline.segments.firstOrNull()?.isCommitted)
        assertTrue(pipeline.stats.hasOpenLine)

        pipeline.commitStaleSegments(now = 1_000 + CaptionStabilizer.DEFAULT_SILENCE_COMMIT_THRESHOLD + 0.5)
        assertEquals(true, pipeline.segments.firstOrNull()?.isCommitted)
        assertEquals(1, pipeline.stats.segmentsCommitted)
        assertFalse(pipeline.stats.hasOpenLine)
    }

    @Test
    fun `while listening, the stale timer commits such a line by itself`() = runTest {
        val engine = FakeEngine()
        val clock = TestClock()
        val (pipeline, _, _) = makePipeline(
            engines = mapOf(whisper to engine),
            audioWatchdog = AudioStallWatchdog.disabled,
            now = { clock.now },
        )
        pipeline.start(AppSettings.default)
        engine.emit(token(UUID.randomUUID(), "מילה", at = clock.now))
        assertTrue(eventually { pipeline.segments.size == 1 })
        clock.advance(CaptionStabilizer.DEFAULT_SILENCE_COMMIT_THRESHOLD + 0.5)
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })
        assertTrue(pipeline.phase.isListening)
    }

    @Test
    fun `a live update, a pause, then the engine's final - the line stays open until the final arrives`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine), now = { 1_000.0 })
        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()
        engine.emit(token(id, "מה שלו", at = 1_000.0))
        assertTrue(eventually { pipeline.segments.size == 1 })

        // A 1 s pause ends the utterance and the careful final pass takes
        // ~1 s more on a phone; the stale timer ticks in between.
        pipeline.commitStaleSegments(now = 1_002.2)
        assertEquals(false, pipeline.segments.firstOrNull()?.isCommitted)

        engine.emit(token(id, "מה שלומך?", final = true, at = 1_002.3))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isCommitted == true })
        assertEquals("מה שלומך?", pipeline.segments.firstOrNull()?.text)
        assertEquals(1, pipeline.stats.segmentsCommitted)
    }

    @Test
    fun `a slow engine's final after the safety net closed its line wakes the screen reader again, yet the line is counted once`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine), now = { 1_000.0 })
        pipeline.start(AppSettings.default)
        val wait = CaptionStabilizer.DEFAULT_SILENCE_COMMIT_THRESHOLD + 0.5

        // The final comes straight after the guess, with a word changed:
        // VoiceOver read the guess, so it has to hear of the correction
        // now, not with whatever line finishes next.
        val corrected = UUID.randomUUID()
        engine.emit(token(corrected, "מה שלו", at = 1_000.0))
        assertTrue(eventually { pipeline.segments.size == 1 })
        pipeline.commitStaleSegments(now = 1_000 + wait)
        assertEquals(1, pipeline.committedLineCount)
        engine.emit(token(corrected, "מה שלומך?", final = true, at = 1_008.0))
        assertTrue(eventually { pipeline.segments.firstOrNull()?.isSettled == true })
        assertEquals(2, pipeline.committedLineCount)
        assertEquals(1, pipeline.stats.segmentsCommitted)

        // Reopened by a live pass, closed by the safety net again, then
        // finished: still one line.
        val reopened = UUID.randomUUID()
        engine.emit(token(reopened, "ביום", at = 1_010.0))
        assertTrue(eventually { pipeline.segments.size == 2 })
        pipeline.commitStaleSegments(now = 1_010 + wait)
        engine.emit(token(reopened, "ביום שלישי", at = 1_018.0))
        assertTrue(eventually { pipeline.segments.lastOrNull()?.text == "ביום שלישי" })
        assertEquals(false, pipeline.segments.lastOrNull()?.isCommitted)
        pipeline.commitStaleSegments(now = 1_018 + wait)
        assertEquals(4, pipeline.committedLineCount)
        engine.emit(token(reopened, "ביום שלישי בבוקר", final = true, at = 1_026.0))
        assertTrue(eventually { pipeline.segments.lastOrNull()?.isSettled == true })
        assertEquals(5, pipeline.committedLineCount)
        assertEquals(2, pipeline.stats.segmentsCommitted)
    }

    @Test
    fun `a slow embedding names the line that was being said, not one that started meanwhile`() = runTest {
        val engine = FakeEngine()
        val audio = FakeAudioCapturer()
        val embedder = GatedEmbedder()
        val pipeline = captionPipeline(
            audio = audio,
            engineFactory = { engine },
            embedder = embedder,
            recovery = AutoRecoveryPolicy.disabled(),
            now = { 1_000.0 },
            embedderContext = Dispatchers.Default,
        )
        pipeline.start(AppSettings.default)

        val first = UUID.randomUUID()
        engine.emit(token(first, "שלום"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventuallyInRealTime { embedder.callsStarted == 1 })

        val second = UUID.randomUUID()
        engine.emit(token(first, "שלום", final = true))
        engine.emit(token(second, "מה נשמע"))
        assertTrue(eventually { pipeline.segments.size == 2 })
        embedder.release()

        assertTrue(eventuallyInRealTime { pipeline.segments.firstOrNull()?.speakerClusterID != null })
        assertNull(pipeline.segments.lastOrNull()?.speakerClusterID)
    }

    @Test
    fun `deleting one of two saved prints under a name stops only that print naming anyone`() = runTest {
        val (pipeline, _, _) = makePipeline()
        val good = SpeakerProfile(name = "Savta", embedding = floatArrayOf(1f, 0f, 0f))
        val wrong = SpeakerProfile(name = "Savta", embedding = floatArrayOf(0f, 1f, 0f))
        pipeline.enroll(good)
        pipeline.enroll(wrong)
        pipeline.forgetProfile(wrong.id)
        assertEquals(1, pipeline.speakerClusters.count { it.name == "Savta" })
        assertContentEquals(floatArrayOf(1f, 0f, 0f), pipeline.speakerClusters.firstOrNull { it.name == "Savta" }?.centroid)
    }

    @Test
    fun `audio windows are embedded and the pending utterance gets a speaker cluster`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)

        val id = UUID.randomUUID()
        engine.emit(token(id, "מי מדבר"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        // 1.5 s at 16 kHz is the embedding window; one positive-led window
        // is "speaker A".
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.segments.firstOrNull()?.speakerClusterID != null })
        assertEquals(1, pipeline.speakerClusters.size)
        assertEquals(1, pipeline.stats.speakerClustersOpened)
        assertEquals(1.5, pipeline.stats.audioSecondsReceived)

        // A different voice on the next utterance opens a second cluster.
        val id2 = UUID.randomUUID()
        engine.emit(token(id, "מי מדבר", final = true))
        engine.emit(token(id2, "אני"))
        assertTrue(eventually { pipeline.segments.size == 2 })
        audio.push(FloatArray(24_000) { -0.5f })
        assertTrue(eventually { pipeline.segments.lastOrNull()?.speakerClusterID == 1 })
        assertEquals(2, pipeline.speakerClusters.size)
        assertTrue(pipeline.displayName(pipeline.segments[0]) != pipeline.displayName(pipeline.segments[1]))
    }

    @Test
    fun `clearing the captions starts the voices over - an unnamed voice is new again, named people stay`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.enroll(SpeakerProfile(name = "דנה", embedding = floatArrayOf(0f, 1f, 0f)))
        pipeline.start(AppSettings.default)
        engine.emit(token(UUID.randomUUID(), "מי מדבר"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.speakerClusters.size == 2 })

        pipeline.clearTranscript()
        assertEquals(listOf<String?>("דנה"), pipeline.speakerClusters.map { it.name })
    }

    @Test
    fun `a speaker found while a line is being written stays on it when the line is closed after a pause`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)

        engine.emit(token(UUID.randomUUID(), "מי מדבר"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.segments.firstOrNull()?.speakerClusterID != null })

        pipeline.commitStaleSegments(now = System.currentTimeMillis() / 1000.0 + 3_600)
        assertEquals(true, pipeline.segments.firstOrNull()?.isCommitted)
        assertNotNull(pipeline.segments.firstOrNull()?.speakerClusterID)
    }

    @Test
    fun `a custom embedder's own recommended threshold is used at the untouched app default, not CAM++'s`() = runTest {
        class ThresholdTestEmbedder : SpeakerEmbedding {
            override val recommendedSimilarityThreshold: Float = 0.75f

            override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? {
                if (samples.isEmpty()) return null
                // Cosine similarity of exactly 0.6 to each other: below this
                // embedder's own 0.75, but above CAM++'s 0.45 default.
                return if (samples[0] > 0) floatArrayOf(1f, 0f) else floatArrayOf(0.6f, 0.8f)
            }
        }
        val engine = FakeEngine()
        val audio = FakeAudioCapturer()
        // The lines' clock, as in makePipeline: on the real clock a line
        // stamped 1,000 is long stale, and the next tick closed it before
        // the voice windows could name it.
        val pipeline = captionPipeline(
            audio = audio,
            engineFactory = { engine },
            embedder = ThresholdTestEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
            now = { 1_000.0 },
        )
        pipeline.start(AppSettings.default)

        val id = UUID.randomUUID()
        engine.emit(token(id, "מי מדבר"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.segments.firstOrNull()?.speakerClusterID != null })
        assertEquals(1, pipeline.speakerClusters.size)

        val id2 = UUID.randomUUID()
        engine.emit(token(id, "מי מדבר", final = true))
        engine.emit(token(id2, "אני"))
        assertTrue(eventually { pipeline.segments.size == 2 })
        // Below its own threshold but not by much: two windows of the new
        // voice make a speaker (a single one is held as a doubtful window).
        audio.push(FloatArray(24_000) { -0.5f })
        audio.push(FloatArray(24_000) { -0.5f })
        assertTrue(eventually { pipeline.segments.lastOrNull()?.speakerClusterID == 1 })
        assertEquals(2, pipeline.speakerClusters.size)
    }

    @Test
    fun `naming a speaker returns the centroid for persistence and renames the cluster`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()
        engine.emit(token(id, "היי"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.segments.firstOrNull()?.speakerClusterID != null })

        val centroid = pipeline.nameSpeaker(pipeline.segments[0], "סבתא")
        assertContentEquals(floatArrayOf(1f, 0f, 0f), centroid)
        assertEquals("סבתא", pipeline.displayName(pipeline.segments[0]))
    }

    @Test
    fun `an enrolled profile names the matching voice from its first window`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.enroll(SpeakerProfile(name = "דנה", embedding = floatArrayOf(1f, 0f, 0f)))
        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()
        engine.emit(token(id, "היי"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.segments.firstOrNull()?.speakerClusterID != null })

        assertEquals("דנה", pipeline.displayName(pipeline.segments[0]))
        assertEquals(0, pipeline.stats.speakerClustersOpened)
    }

    @Test
    fun `with no voice model to compare against, no saved voice print is called out of date`() = runTest {
        class NoModel : SpeakerEmbedding {
            override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? = null
        }
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { FakeEngine() },
            embedder = NoModel(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        assertTrue(pipeline.canRecognize(SpeakerProfile(name = "דנה", embedding = floatArrayOf(1f, 0f))))
    }

    @Test
    fun `a profile saved by a different, since-replaced embedder is not seeded as a phantom speaker`() = runTest {
        val engine = FakeEngine()
        val (pipeline, audio, _) = makePipeline(engines = mapOf(whisper to engine))
        // FakeEmbedder always returns length-3 vectors; this profile is
        // from a shorter, older embedder and can never match live speech.
        val old = SpeakerProfile(name = "דנה", embedding = floatArrayOf(1f, 0f))
        assertFalse(pipeline.canRecognize(old))
        assertTrue(pipeline.canRecognize(SpeakerProfile(name = "דנה", embedding = floatArrayOf(1f, 0f, 0f))))
        pipeline.enroll(old)
        assertTrue(pipeline.speakerClusters.isEmpty())

        pipeline.start(AppSettings.default)
        val id = UUID.randomUUID()
        engine.emit(token(id, "היי"))
        assertTrue(eventually { pipeline.segments.size == 1 })
        audio.push(FloatArray(24_000) { 0.5f })
        assertTrue(eventually { pipeline.segments.firstOrNull()?.speakerClusterID != null })

        assertTrue(pipeline.displayName(pipeline.segments[0]) != "דנה")
        assertEquals(1, pipeline.stats.speakerClustersOpened)
    }

    @Test
    fun `clearTranscript empties segments but keeps listening`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        engine.emit(token(UUID.randomUUID(), "x"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()

        assertTrue(pipeline.segments.isEmpty())
        assertEquals(PipelinePhase.Listening, pipeline.phase)
    }

    @Test
    fun `a sentence still being said when the screen is cleared keeps the words said after the tap, without the cleared ones`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "את המספר של"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "את המספר של הרופא"))
        engine.emit(token(open, "את המספר של הרופא שלך", final = true))
        engine.emit(token(UUID.randomUUID(), "שלום", final = true))
        assertTrue(eventually { pipeline.segments.size == 2 })
        assertEquals(listOf("הרופא שלך", "שלום"), pipeline.segments.map { it.text })
    }

    @Test
    fun `clearing twice in one sentence keeps neither cleared part and still shows what came after`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "את המספר של"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "את המספר של הרופא"))
        assertTrue(eventually { pipeline.segments.map { it.text } == listOf("הרופא") })
        pipeline.clearTranscript()
        engine.emit(token(open, "את המספר של הרופא שלך", final = true))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertEquals(listOf("שלך"), pipeline.segments.map { it.text })
    }

    @Test
    fun `a second clear after the engine briefly sent a shorter version still keeps every cleared word away`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "את המספר של הרופא שלך"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "את המספר של הרופא שלך מחר"))
        assertTrue(eventually { pipeline.segments.map { it.text } == listOf("מחר") })
        engine.emit(token(open, "את המספר של הרופא"))
        assertTrue(eventually { pipeline.stats.tokensReceived == 3 })
        assertEquals(listOf("מחר"), pipeline.segments.map { it.text })
        pipeline.clearTranscript()
        engine.emit(token(open, "את המספר של הרופא שלך מחר בבוקר", final = true))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertEquals(listOf("בבוקר"), pipeline.segments.map { it.text })
    }

    @Test
    fun `a cleared sentence the engine rewrites heavily comes back whole rather than losing what followed`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "את המספר של"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "עם מספר שלו הרופא", final = true))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertEquals(listOf("עם מספר שלו הרופא"), pipeline.segments.map { it.text })
    }

    @Test
    fun `a finished line that turns out to be only cleared words still finishes what was shown after the tap, at once`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "את המספר של"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "את המספר של הרופא שלך"))
        assertTrue(eventually { pipeline.segments.map { it.text } == listOf("הרופא שלך") })
        assertEquals(false, pipeline.segments.firstOrNull()?.isCommitted)
        engine.emit(token(open, "עם המספר של", final = true))
        assertTrue(eventually(within = 2.seconds) { pipeline.segments.firstOrNull()?.isCommitted == true })
        assertEquals(listOf("הרופא שלך"), pipeline.segments.map { it.text })
    }

    @Test
    fun `clearing again after a rewrite came back whole keeps it away, instead of counting the first cleared words twice`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "את המספר של"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "עם מספר שלו הרופא"))
        assertTrue(eventually { pipeline.segments.map { it.text } == listOf("עם מספר שלו הרופא") })
        pipeline.clearTranscript()
        engine.emit(token(open, "עם מספר שלו הרופא שלך", final = true))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertEquals(listOf("שלך"), pipeline.segments.map { it.text })
    }

    @Test
    fun `a cleared sentence finished with a word written differently still keeps the cleared words away, and shows only what came after`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "אני צריך לקבוע תור לרופא עיניים"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "אני צריכה לקבוע תור לרופא עיניים ביום שלישי", final = true))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertEquals(listOf("ביום שלישי"), pipeline.segments.map { it.text })

        val other = UUID.randomUUID()
        engine.emit(token(other, "את המספר של"))
        assertTrue(eventually { pipeline.segments.size == 2 })
        pipeline.clearTranscript()
        engine.emit(token(other, "עם המספר של", final = true))
        engine.emit(token(UUID.randomUUID(), "שלום", final = true))
        assertTrue(eventually { pipeline.segments.map { it.text } == listOf("שלום") })
    }

    @Test
    fun `a cleared sentence finished with one cleared word left out still keeps the cleared words away`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "קבענו תור מחר"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "קבענו מחר בבוקר מוקדם", final = true))
        assertTrue(eventually { pipeline.segments.size == 1 })
        assertEquals(listOf("בבוקר מוקדם"), pipeline.segments.map { it.text })
    }

    @Test
    fun `a cleared sentence the cloud finishes as one line per speaker keeps the cleared words away from the second speaker's line too`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "שלום מה שלומך אני בסדר"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "שלום מה שלומך", final = true))
        engine.emit(TranscriptToken(UUID.randomUUID(), "אני בסדר גמור", isFinal = true, timestamp = 1_000.0, startsNewSpeakerTurn = true))
        engine.emit(TranscriptToken(UUID.randomUUID(), "איפה היית", isFinal = true, timestamp = 1_000.0, startsNewSpeakerTurn = true))
        assertTrue(eventually { pipeline.segments.size == 2 })
        assertEquals(listOf("גמור", "איפה היית"), pipeline.segments.map { it.text })
    }

    @Test
    fun `words shown after a clear are not shown twice when the cloud finishes the sentence with a second speaker`() = runTest {
        val engine = FakeEngine()
        val (pipeline, _, _) = makePipeline(engines = mapOf(whisper to engine))
        pipeline.start(AppSettings.default)
        val open = UUID.randomUUID()
        engine.emit(token(open, "שלום מה שלומך אני"))
        assertTrue(eventually { pipeline.segments.size == 1 })

        pipeline.clearTranscript()
        engine.emit(token(open, "שלום מה שלומך אני בסדר גמור"))
        assertTrue(eventually { pipeline.segments.map { it.text } == listOf("בסדר גמור") })
        engine.emit(token(open, "שלום מה", final = true))
        engine.emit(TranscriptToken(UUID.randomUUID(), "שלומך אני בסדר גמור ואתה", isFinal = true, timestamp = 1_000.0, startsNewSpeakerTurn = true))
        engine.emit(token(UUID.randomUUID(), "איפה היית", final = true))
        assertTrue(eventually { pipeline.segments.lastOrNull()?.text == "איפה היית" })
        assertEquals(listOf("בסדר גמור", "ואתה", "איפה היית"), pipeline.segments.map { it.text })
    }
}
