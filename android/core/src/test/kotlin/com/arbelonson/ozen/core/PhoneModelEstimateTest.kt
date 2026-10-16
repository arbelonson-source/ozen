package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneModelEstimateTest {
    private fun model(name: String) = PhoneModelCatalog.all.first { it.file.contains(name) }

    private fun speedFor(model: PhoneModel, passSeconds: Double): Double = model.passGflop / (passSeconds * PhoneModelEstimate.REAL_PASS_SHARE)

    @Test
    fun `a pass takes the model's compute cost over the phone's measured speed`() {
        val turbo = model("large-v3-turbo-q5_0")
        val fast = PhoneModelEstimate.passSeconds(turbo, 400.0)
        val slow = PhoneModelEstimate.passSeconds(turbo, 100.0)
        assertEquals(4.0, slow / fast, 1e-9)
        assertEquals(turbo.passGflop / (400.0 * PhoneModelEstimate.REAL_PASS_SHARE), fast, 1e-9)
    }

    @Test
    fun `a model keeps up when a pass fits the live rhythm, falls a little behind within twice that, and is too slow beyond`() {
        val small = model("small")
        assertEquals(PhoneModelFit.KeepsUp, PhoneModelEstimate.fit(small, speedFor(small, PhoneModelEstimate.KEEPS_UP_SECONDS - 0.05)))
        assertEquals(PhoneModelFit.KeepsUp, PhoneModelEstimate.fit(small, speedFor(small, PhoneModelEstimate.KEEPS_UP_SECONDS)))
        assertEquals(PhoneModelFit.ALittleBehind, PhoneModelEstimate.fit(small, speedFor(small, PhoneModelEstimate.KEEPS_UP_SECONDS + 0.05)))
        assertEquals(PhoneModelFit.ALittleBehind, PhoneModelEstimate.fit(small, speedFor(small, PhoneModelEstimate.BEHIND_SECONDS)))
        assertEquals(PhoneModelFit.TooSlow, PhoneModelEstimate.fit(small, speedFor(small, PhoneModelEstimate.BEHIND_SECONDS + 0.05)))
        assertEquals(2.0, PhoneModelEstimate.BEHIND_SECONDS / PhoneModelEstimate.KEEPS_UP_SECONDS, 1e-9)
    }

    @Test
    fun `a speed that is zero, negative or not a number keeps up with nothing`() {
        for (speed in listOf(0.0, -5.0, Double.NaN)) {
            for (model in PhoneModelCatalog.all) assertEquals(PhoneModelFit.TooSlow, PhoneModelEstimate.fit(model, speed))
        }
    }

    @Test
    fun `a heavier model is never judged better than a lighter one on the same phone`() {
        for (speed in listOf(5.0, 20.0, 60.0, 150.0, 400.0, 1500.0)) {
            val ordered = PhoneModelCatalog.all.sortedBy { it.passGflop }.map { PhoneModelEstimate.fit(it, speed).ordinal }
            assertEquals(ordered.sorted(), ordered, "at $speed")
        }
    }

    @Test
    fun `a very fast phone is recommended the most accurate model for the language`() {
        assertEquals("ggml-ozen-a3-q5_0.bin", PhoneModelEstimate.recommend(UILanguage.Hebrew, 5_000.0).file)
        assertEquals("ggml-large-v3-turbo-q5_0.bin", PhoneModelEstimate.recommend(UILanguage.English, 5_000.0).file)
    }

    @Test
    fun `a middling phone is recommended the best model that keeps up, which is a lighter one`() {
        val small = model("small")
        val turbo = model("large-v3-turbo-q5_0")
        val speed = speedFor(small, 1.0)
        assertEquals(PhoneModelFit.KeepsUp, PhoneModelEstimate.fit(small, speed))
        assertTrue(PhoneModelEstimate.fit(turbo, speed) != PhoneModelFit.KeepsUp)
        assertEquals(small.file, PhoneModelEstimate.recommend(UILanguage.English, speed).file)
        assertEquals(small.file, PhoneModelEstimate.recommend(UILanguage.Hebrew, speed).file)
    }

    @Test
    fun `the Hebrew only models are never recommended for another language, however fast the phone`() {
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            for (speed in listOf(1.0, 50.0, 5_000.0)) {
                assertTrue(!PhoneModelEstimate.recommend(language, speed).isHebrewOnly)
            }
        }
    }

    @Test
    fun `when nothing keeps up the best one that is only a little behind is recommended, and when even that fails the lightest`() {
        val base = model("base")
        val behind = speedFor(base, (PhoneModelEstimate.KEEPS_UP_SECONDS + PhoneModelEstimate.BEHIND_SECONDS) / 2)
        assertEquals(PhoneModelFit.ALittleBehind, PhoneModelEstimate.fit(base, behind))
        assertTrue(PhoneModelCatalog.all.none { it !== base && PhoneModelEstimate.fit(it, behind) != PhoneModelFit.TooSlow })
        assertEquals(base.file, PhoneModelEstimate.recommend(UILanguage.English, behind).file)
        assertEquals(base.file, PhoneModelEstimate.recommend(UILanguage.English, speedFor(base, PhoneModelEstimate.BEHIND_SECONDS * 4)).file)
        assertEquals(base.file, PhoneModelEstimate.recommend(UILanguage.Hebrew, 0.5).file)
    }

    @Test
    fun `without a measurement the usual recommendation stands`() {
        assertEquals("ggml-ozen-a3-q5_0.bin", PhoneModelEstimate.recommend(UILanguage.Hebrew, null).file)
        assertEquals("ggml-large-v3-turbo-q5_0.bin", PhoneModelEstimate.recommend(UILanguage.Russian, null).file)
        assertEquals("ggml-large-v3-turbo-q5_0.bin", PhoneModelEstimate.recommend(UILanguage.Russian, Double.NaN).file)
    }

    @Test
    fun `on the test emulator, measured at 77 GFLOPS, the estimate agrees within a tenth with the real pass of each model`() {
        val measured = mapOf("ggml-ozen-a3-q5_0.bin" to 6.23, "ggml-large-v3-turbo-q5_0.bin" to 6.20, "ggml-small-q5_1.bin" to 1.01, "ggml-base-q5_1.bin" to 0.26)
        for ((file, seconds) in measured) {
            val estimate = PhoneModelEstimate.passSeconds(PhoneModelCatalog.byFile(file)!!, 77.2)
            assertEquals(seconds, estimate, maxOf(0.1 * seconds, 0.05), file)
        }
    }

    @Test
    fun `that emulator is told Turbo is too slow and Small and Base keep up, and is recommended Small for Hebrew and for English`() {
        val labels = PhoneModelCatalog.all.associate { it.file to PhoneModelEstimate.fit(it, 77.2) }
        assertEquals(PhoneModelFit.TooSlow, labels.getValue("ggml-ozen-a3-q5_0.bin"))
        assertEquals(PhoneModelFit.TooSlow, labels.getValue("ggml-large-v3-turbo-q5_0.bin"))
        assertEquals(PhoneModelFit.KeepsUp, labels.getValue("ggml-small-q5_1.bin"))
        assertEquals(PhoneModelFit.KeepsUp, labels.getValue("ggml-base-q5_1.bin"))
        assertEquals("ggml-small-q5_1.bin", PhoneModelEstimate.recommend(UILanguage.Hebrew, 77.2).file)
        assertEquals("ggml-small-q5_1.bin", PhoneModelEstimate.recommend(UILanguage.English, 77.2).file)
    }

    @Test
    fun `a phone four times as fast as that emulator is still a little behind on Turbo, and one six times as fast keeps up`() {
        assertEquals(PhoneModelFit.ALittleBehind, PhoneModelEstimate.fit(model("large-v3-turbo-q5_0"), 77.2 * 4))
        assertEquals(PhoneModelFit.KeepsUp, PhoneModelEstimate.fit(model("large-v3-turbo-q5_0"), 77.2 * 6))
        assertEquals("ggml-ozen-a3-q5_0.bin", PhoneModelEstimate.recommend(UILanguage.Hebrew, 77.2 * 6).file)
    }

    @Test
    fun `a mid-range phone is never recommended a Turbo sized model`() {
        for (speed in listOf(20.0, 40.0, 80.0, 125.0, 160.0)) {
            for (language in listOf(UILanguage.Hebrew, UILanguage.English)) {
                val file = PhoneModelEstimate.recommend(language, speed).file
                assertTrue(file == "ggml-small-q5_1.bin" || file == "ggml-base-q5_1.bin", "$speed $language $file")
            }
        }
    }
}
