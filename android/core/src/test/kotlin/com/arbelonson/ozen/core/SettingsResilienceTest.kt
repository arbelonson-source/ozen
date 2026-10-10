package com.arbelonson.ozen.core

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private const val PROFILE_ID = "7C9E6679-7425-40DE-944B-E07FC1F90AE7"

private fun decode(json: String): AppSettings = AppSettings.fromJson(json)

class SettingsResilienceTest {
    @Test
    fun `an engine name from a newer build falls back to the default and keeps the enrolled voices`() {
        val settings = decode(
            """{"engine":"someFutureEngine","speakerProfiles":[{"id":"$PROFILE_ID","name":"דנה","embedding":[0.1,0.2,0.3]}],"vocabulary":["אביטל"],"hasCompletedOnboarding":true}""",
        )
        assertEquals(AppSettings.default.engine, settings.engine)
        assertEquals(listOf("דנה"), settings.speakerProfiles.map { it.name })
        assertEquals(listOf("אביטל"), settings.vocabulary)
        assertTrue(settings.hasCompletedOnboarding)
    }

    @Test
    fun `one damaged voice profile is dropped and the others are kept`() {
        val settings = decode(
            """{"speakerProfiles":[{"id":"$PROFILE_ID","name":"דנה","embedding":[0.1,0.2]},{"name":"בלי מזהה","embedding":[0.3]},{"id":"not-a-uuid","name":"שבור","embedding":"x"},{"id":"${UUID.randomUUID().toString().uppercase()}","name":"יוסי","embedding":[0.4,0.5]}]}""",
        )
        assertEquals(listOf("דנה", "יוסי"), settings.speakerProfiles.map { it.name })
    }

    @Test
    fun `an empty voice print is dropped rather than kept to fail later`() {
        val settings = decode("""{"speakerProfiles":[{"id":"$PROFILE_ID","name":"ריק","embedding":[]}]}""")
        assertTrue(settings.speakerProfiles.isEmpty())
    }

    @Test
    fun `an unreadable display value only resets that value`() {
        val settings = decode("""{"display":{"fontSize":48,"theme":"neon","boldText":true},"saveHistory":false}""")
        assertEquals(48.0, settings.display.fontSize)
        assertEquals(DisplayPreferences.default.theme, settings.display.theme)
        assertTrue(settings.display.boldText)
        assertEquals(false, settings.saveHistory)
    }

    @Test
    fun `a value of the wrong type resets just that setting`() {
        val settings = decode(
            """{"saveHistory":"yes please","speechRate":0.3,"keywordAlerts":[{"id":"$PROFILE_ID","phrase":"סבתא"},42,{"phrase":"בלי מזהה"}],"soundAlerts":{"isEnabled":false,"minimumImportance":"loud"}}""",
        )
        assertEquals(AppSettings.default.saveHistory, settings.saveHistory)
        assertEquals(0.3f, settings.speechRate)
        assertEquals(listOf("סבתא"), settings.keywordAlerts.map { it.phrase })
        assertEquals(false, settings.soundAlerts.isEnabled)
        assertEquals(SoundAlertPreferences.default.minimumImportance, settings.soundAlerts.minimumImportance)
    }

    @Test
    fun `settings read back from damaged files can be saved again`() {
        val settings = decode(
            """{"engine":"someFutureEngine","speakerProfiles":[{"id":"$PROFILE_ID","name":"דנה","embedding":[0.1,0.2]}]}""",
        )
        val folder = File(System.getProperty("java.io.tmpdir"), "ozen-resilience-${UUID.randomUUID()}")
        try {
            val store = SettingsStore(File(folder, "settings.json"))
            store.save(settings)
            assertEquals(listOf("דנה"), store.load().speakerProfiles.map { it.name })
        } finally {
            folder.deleteRecursively()
        }
    }
}

class TranscriptRecordResilienceTest {
    @Test
    fun `an unknown engine and one damaged line still load the rest of the conversation`() {
        val id = UUID.randomUUID().toString().uppercase()
        val json = """
            {"id":"$id","startedAt":100,"engine":"someFutureEngine","segments":[
              {"id":"${UUID.randomUUID().toString().uppercase()}","text":"שלום","startTimestamp":100,"isCommitted":true},
              {"id":"${UUID.randomUUID().toString().uppercase()}","text":42,"startTimestamp":101,"isCommitted":true},
              {"id":"${UUID.randomUUID().toString().uppercase()}","text":"להתראות","startTimestamp":102,"isCommitted":true}
            ],"title":7}
        """.trimIndent()
        val record = TranscriptSessionRecord.fromJson(json)
        assertEquals(id, record.id.toString().uppercase())
        assertEquals(listOf("שלום", "להתראות"), record.segments.map { it.text })
        assertEquals(TranscriptionEngineKind.WhisperKit, record.engine)
        assertNull(record.title)
    }
}

class EnrollmentNonFiniteTest {
    private class NaNEmbedder : SpeakerEmbedding {
        override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? = floatArrayOf(0.1f, Float.NaN, 0.3f)
    }

    @Test
    fun `a voice print with NaN in it is refused, so it can never break saving settings`() = runTest {
        val pipeline = captionPipeline(audio = FakeAudioCapturer(), engineFactory = { FakeEngine() }, embedder = NaNEmbedder())
        assertNull(pipeline.embedding(FloatArray(96_000) { 0.2f }))
    }
}

class EnrollmentSpeechOnlyTest {
    /**
     * The "voice print" is the window's loudness, so a test can see
     * exactly which audio went into it.
     */
    private class LoudnessEmbedder : SpeakerEmbedding {
        override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? =
            floatArrayOf(EnergyVoiceDetector.rms(samples))
    }

    private fun TestScope.pipeline(): CaptionPipeline =
        captionPipeline(audio = FakeAudioCapturer(), engineFactory = { FakeEngine() }, embedder = LoudnessEmbedder())

    private fun speech(seconds: Double, amplitude: Float = 0.3f): FloatArray =
        FloatArray((seconds * 16_000).toInt()) { if (it % 2 == 0) amplitude else -amplitude }

    private fun silence(seconds: Double): FloatArray = FloatArray((seconds * 16_000).toInt())

    @Test
    fun `windows of another voice (the TV, a relative) are left out of the saved voice`() {
        val her = floatArrayOf(1f, 0f, 0f)
        val tv = floatArrayOf(0f, 1f, 0f)
        val print = CaptionPipeline.consistentAverage(List(7) { her } + listOf(tv, tv))
        assertTrue(cosineSimilarity(print, her) > 0.999)
    }

    @Test
    fun `when most of the recording disagrees, the plain average stands`() {
        val one = floatArrayOf(1f, 0f, 0f)
        val other = floatArrayOf(0f, 1f, 0f)
        val print = CaptionPipeline.consistentAverage(listOf(one, one, one, other, other, other))
        assertTrue(abs(print[0] - 0.5f) < 0.001 && abs(print[1] - 0.5f) < 0.001)
    }

    @Test
    fun `when most windows sound unlike the rest, none are dropped and the plain average stands`() {
        val her = floatArrayOf(1f, 0f, 0f, 0f, 0f)
        val others = listOf(
            floatArrayOf(0f, 1f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, 0f, 0f, 1f),
        )
        val print = CaptionPipeline.consistentAverage(listOf(her, her, her) + others)
        assertTrue(abs(print[0] - 3.0f / 7) < 0.001)
        assertTrue((1..4).all { abs(print[it] - 1.0f / 7) < 0.001 })
    }

    @Test
    fun `a recording nobody spoke in makes no voice print`() = runTest {
        assertNull(pipeline().embedding(silence(30.0)))
        // A quiet room: nothing near speech level.
        assertNull(pipeline().embedding(speech(30.0, amplitude = 0.002f)))
    }

    @Test
    fun `a couple of seconds of speech isn't enough, a few more is`() = runTest {
        assertNull(pipeline().embedding(speech(3.0) + silence(27.0)))
        assertNotNull(pipeline().embedding(speech(4.5) + silence(25.5)))
    }

    @Test
    fun `pauses between sentences don't water the voice down`() = runTest {
        val recording = speech(6.0) + silence(6.0) + speech(6.0) + silence(12.0)
        val print = assertNotNull(pipeline().embedding(recording))
        // The whole recording's loudness would be about 0.19.
        assertTrue(abs(print[0] - 0.3f) < 0.001)
    }

    @Test
    fun `the voice print made off the main thread is the same one`() = runTest {
        val recording = speech(6.0) + silence(3.0) + speech(3.0)
        val pipeline = pipeline()
        val inBackground = pipeline.embeddingInBackground(recording)
        assertNotNull(inBackground)
        assertTrue(inBackground.contentEquals(pipeline.embedding(recording)))
        assertNull(pipeline.embeddingInBackground(silence(30.0)))
    }

    private class CountingEmbedder : SpeakerEmbedding {
        private val calls = AtomicInteger()
        val embedCalls: Int get() = calls.get()
        override val embeddingLength: Int? get() = 3

        override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? {
            calls.incrementAndGet()
            return floatArrayOf(1f, 0f, 0f)
        }
    }

    // Saved profiles are checked when the app opens, on the main thread:
    // running a neural model there to learn its print length froze launch.
    @Test
    fun `checking saved voices at launch doesn't run the speaker model`() = runTest {
        val embedder = CountingEmbedder()
        val pipeline = captionPipeline(audio = FakeAudioCapturer(), engineFactory = { FakeEngine() }, embedder = embedder)
        pipeline.enroll(SpeakerProfile(name = "Savta", embedding = floatArrayOf(1f, 0f, 0f)))
        pipeline.enroll(SpeakerProfile(name = "Old", embedding = FloatArray(12) { 1f }))
        assertEquals(0, embedder.embedCalls)
        assertEquals(listOf("Savta"), pipeline.speakerClusters.map { it.name })
    }
}
