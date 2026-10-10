package com.arbelonson.ozen.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.VoiceEvidence
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SileroVoiceScorerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun scores(samples: FloatArray): List<Float> {
        val scorer = checkNotNull(SileroVoiceScorer.fromAssets(instrumentation.targetContext)) { "the bundled model didn't load" }
        val scores = ArrayList<Float>()
        scorer.use {
            val evidence = VoiceEvidence { input -> scorer.score(input)?.also { scores.add(it) } }
            evidence.append(samples)
        }
        assertEquals(samples.size / VoiceEvidence.CHUNK_SAMPLES, scores.size)
        return scores
    }

    private fun voiced(samples: FloatArray) = scores(samples).count { it >= VoiceEvidence.VOICE_THRESHOLD }

    private fun fixture(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open(name).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (id == "data") {
                return FloatArray(size / 2) { buffer.getShort(offset + 8 + it * 2) / 32768f }
            }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in $name")
    }

    @Test
    fun theBundledModelLoads() {
        SileroVoiceScorer.fromAssets(instrumentation.targetContext).use { assertNotNull(it) }
    }

    @Test
    fun hearsAVoiceInEachRecordedSpeaker() {
        for (name in listOf("speakerA_clip1.wav", "speakerA_clip2.wav", "speakerB_clip1.wav")) {
            val scores = scores(fixture(name))
            assertEquals(name, 7, scores.size)
            assertTrue("$name: $scores", scores.count { it >= VoiceEvidence.VOICE_THRESHOLD } >= 4)
        }
    }

    @Test
    fun scoresAChunkAsItsEightFramesTogetherTheWayTheVoiceGateMeasurementsAssume() {
        val scores = scores(fixture("speakerB_clip1.wav"))
        val measured = listOf(0.0472f, 0.1113f, 1f, 1f, 1f, 1f, 1f)
        assertEquals(measured.size, scores.size)
        for ((score, expected) in scores.zip(measured)) assertTrue("$scores", abs(score - expected) < 0.01f)
    }

    @Test
    fun hearsNoneInHissHumOrSilence() {
        var state = 3uL
        val hiss = FloatArray(64_000) {
            state = state * 6364136223846793005uL + 1442695040888963407uL
            ((state shr 40).toFloat() / (1 shl 24).toFloat() - 0.5f) * 0.0195f
        }
        val hum = FloatArray(64_000) { (0.03 * sin(2 * PI * 100 * it / 16_000)).toFloat() }
        val silence = FloatArray(16_384)
        assertEquals(0, voiced(hiss))
        assertEquals(0, voiced(hum))
        assertEquals(0, voiced(silence))
    }
}
