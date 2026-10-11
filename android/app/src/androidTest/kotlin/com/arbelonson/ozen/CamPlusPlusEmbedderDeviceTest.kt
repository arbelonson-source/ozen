package com.arbelonson.ozen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class CamPlusPlusEmbedderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val embedder = CamPlusPlusEmbedder(instrumentation.targetContext)
    private val clips = listOf("speakerA_clip1", "speakerA_clip2", "speakerB_clip1")

    private fun clip(name: String): FloatArray {
        val bytes = ByteBuffer.wrap(instrumentation.context.assets.open("$name.wav").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(offset + it) }, Charsets.US_ASCII)
            val size = bytes.getInt(offset + 4)
            if (id == "data") return FloatArray(size / 2) { bytes.getShort(offset + 8 + it * 2) / 32768f }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in $name")
    }

    private fun print(name: String) = embedder.embed(clip(name), 16_000.0)!!

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var left = 0.0
        var right = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            left += a[i] * a[i]
            right += b[i] * b[i]
        }
        return dot / sqrt(left) / sqrt(right)
    }

    @Test
    fun eachClipsPrintIsTheReferenceModelsOwnOnTheSameWindow() {
        val reference = JSONObject(instrumentation.context.assets.open("campp_148_prints.json").bufferedReader().use { it.readText() }).getJSONObject("prints")
        for (name in clips) {
            val expected = reference.getJSONArray(name).let { values -> FloatArray(values.length()) { values.getDouble(it).toFloat() } }
            val printed = print(name)
            assertEquals(embedder.embeddingLength, printed.size)
            val similarity = cosine(printed, expected)
            assertTrue("$name: $similarity", similarity > 0.999)
        }
    }

    @Test
    fun twoClipsOfOneVoiceAreKeptTogetherAndAnotherVoiceApart() {
        val threshold = AppSettings.default.speakerSimilarityThreshold
        val (first, second, other) = clips.map(::print)
        val same = cosine(first, second)
        assertTrue("one voice scored $same", same >= threshold)
        assertTrue("another voice scored ${cosine(first, other)}", cosine(first, other) < threshold)
        assertTrue("another voice scored ${cosine(second, other)}", cosine(second, other) < threshold)
    }

    @Test
    fun lessThanOneFrameOfSoundGivesNoPrint() {
        assertNull(embedder.embed(FloatArray(100), 16_000.0))
    }
}
