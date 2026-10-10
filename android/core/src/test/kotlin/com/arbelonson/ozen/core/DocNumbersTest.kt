package com.arbelonson.ozen.core

import java.io.File
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The numbers the README and the troubleshooting guide promise her,
 * checked against the code that keeps them. Each phrase is built from the
 * code's value where it can be, so changing one without the other fails
 * here. Whitespace is flattened, so rewrapping a paragraph changes nothing.
 */
class DocNumbersTest {
    private val root = File(System.getProperty("ozen.fixtures")).parentFile.parentFile
    private val words = mapOf(
        1 to "one", 2 to "two", 3 to "three", 4 to "four", 5 to "five", 6 to "six", 7 to "seven", 8 to "eight",
        9 to "nine", 10 to "ten", 11 to "eleven", 12 to "twelve", 15 to "fifteen", 20 to "twenty",
    )

    private fun doc(path: String): String =
        File(root, path).readText(Charsets.UTF_8).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun word(number: Int, capitalized: Boolean = false): String {
        val spelled = words[number] ?: "$number"
        return if (capitalized) spelled.replaceFirstChar { it.uppercase() } else spelled
    }

    @Test
    fun `the home computer, the better-model offer, voices and the voice levels in the report`() = runTest {
        val readme = doc("README.md")
        val guide = doc("docs/troubleshooting.md")
        val captions = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { FakeEngine() },
            embedder = FakeEmbedder(),
            recovery = AutoRecoveryPolicy.disabled(),
        )
        val wait = captions.homeServerWaitSeconds.toInt()
        assertTrue(readme.contains("within about $wait seconds of it answering"))
        assertTrue(guide.contains("within about $wait seconds of it answering"))
        assertTrue(doc("server/README.md").contains("within about $wait seconds of the server answering"))
        val snooze = AppSettings.betterModelOfferSnoozeDays
        assertTrue(snooze.zip(snooze.drop(1)).all { (a, b) -> a < b })
        assertTrue(guide.contains("for ${word(assertNotNull(snooze.firstOrNull()))} days, then for longer each time"))
        assertTrue(guide.contains("more than ${word(EmbeddingClusterer.ACTIVE_UNNAMED_LIMIT)} unnamed voices"))
        val detector = EnergyVoiceDetector()
        fun decibels(ratio: Float): Int = (20 * log10(ratio)).roundToInt()
        assertTrue(guide.contains("about ${decibels(detector.absoluteThreshold)} dBFS"))
        assertTrue(guide.contains("about ${decibels(detector.steadyNoiseFloorRatio)} dB when the noise is steady"))
        assertTrue(guide.contains("up to ${decibels(detector.noiseFloorRatio)} dB when it swings"))
    }
}
