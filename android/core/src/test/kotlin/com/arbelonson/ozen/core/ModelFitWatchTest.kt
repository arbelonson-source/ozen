package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelFitWatchTest {
    private fun model(name: String) = PhoneModelCatalog.all.first { it.file.contains(name) }

    private val turbo get() = model("large-v3-turbo-q5_0")
    private val ozen get() = model("ozen-a3")
    private val small get() = model("small")
    private val base get() = model("base")
    private val phone = "Pixel 7"
    private val plenty = 12_000_000_000L

    private fun measured(model: PhoneModel, averageSeconds: Double) =
        ModelFitWatch.measurement(model, 20, averageSeconds * 20, now = 100.0)

    private fun offer(
        measurement: LiveMeasurement?,
        current: PhoneModel,
        language: UILanguage = UILanguage.Hebrew,
        dismissed: Collection<String> = emptyList(),
        device: String = phone,
    ) = ModelFitWatch.offer(measurement, current, language, plenty, dismissed, device)

    @Test
    fun `a pass on a cool phone on the phone's own model counts`() {
        assertTrue(ModelFitWatch.counts(1.0, DeviceHeat.Nominal, false, TranscriptionEngineKind.WhisperKit))
        assertTrue(ModelFitWatch.counts(1.0, DeviceHeat.Fair, false, TranscriptionEngineKind.WhisperKit))
    }

    @Test
    fun `a pass taken hot does not count`() {
        assertFalse(ModelFitWatch.counts(1.0, DeviceHeat.Serious, false, TranscriptionEngineKind.WhisperKit))
        assertFalse(ModelFitWatch.counts(1.0, DeviceHeat.Critical, false, TranscriptionEngineKind.WhisperKit))
    }

    @Test
    fun `a pass taken in battery saver does not count`() {
        assertFalse(ModelFitWatch.counts(1.0, DeviceHeat.Nominal, true, TranscriptionEngineKind.WhisperKit))
    }

    @Test
    fun `a pass of another engine does not count`() {
        for (engine in TranscriptionEngineKind.entries.filter { it != TranscriptionEngineKind.WhisperKit }) {
            assertFalse(ModelFitWatch.counts(1.0, DeviceHeat.Nominal, false, engine), engine.name)
        }
    }

    @Test
    fun `a pass with no usable time does not count`() {
        for (seconds in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(ModelFitWatch.counts(seconds, DeviceHeat.Nominal, false, TranscriptionEngineKind.WhisperKit), "$seconds")
        }
    }

    @Test
    fun `nineteen passes save nothing and twenty do`() {
        assertFalse(ModelFitWatch.shouldSave(19))
        assertTrue(ModelFitWatch.shouldSave(20))
        assertTrue(ModelFitWatch.shouldSave(35))
        assertNull(ModelFitWatch.measurement(turbo, 19, 40.0, now = 1.0))
        assertNotNull(ModelFitWatch.measurement(turbo, 20, 40.0, now = 1.0))
    }

    @Test
    fun `the measurement keeps the average and the speed it implies for the model that ran`() {
        val saved = ModelFitWatch.measurement(turbo, 20, 80.0, now = 123.0)!!
        assertEquals(turbo.file, saved.file)
        assertEquals(4.0, saved.averageSeconds, 1e-9)
        assertEquals(20, saved.passes)
        assertEquals(123.0, saved.measuredAt, 0.0)
        assertEquals(PhoneModelFit.TooSlow, PhoneModelEstimate.fit(turbo, saved.gflops))
        assertEquals(4.0, PhoneModelEstimate.passSeconds(turbo, saved.gflops), 1e-9)
    }

    @Test
    fun `a model that keeps up gets no offer`() {
        assertNull(offer(measured(turbo, 1.0), turbo, UILanguage.English))
    }

    @Test
    fun `no measurement gets no offer`() {
        assertNull(offer(null, turbo, UILanguage.English))
    }

    @Test
    fun `a model a little behind is offered the lighter one that keeps up`() {
        val chosen = offer(measured(turbo, 2.0), turbo, UILanguage.English)
        assertEquals(small.file, chosen?.file)
    }

    @Test
    fun `a model that is too slow is offered the lighter one that keeps up`() {
        val chosen = offer(measured(ozen, 6.0), ozen)
        assertNotNull(chosen)
        assertTrue(chosen.passGflop < ozen.passGflop)
        assertEquals(PhoneModelFit.KeepsUp, PhoneModelEstimate.fit(chosen, measured(ozen, 6.0)!!.gflops))
    }

    @Test
    fun `a lighter model that is itself too slow is not offered`() {
        val measurement = measured(ozen, 120.0)!!
        assertEquals(PhoneModelFit.TooSlow, PhoneModelEstimate.fit(base, measurement.gflops, plenty))
        assertNull(offer(measurement, ozen))
        assertNull(offer(measured(turbo, 120.0), turbo, UILanguage.English))
    }

    @Test
    fun `a model behind with nothing lighter gets no offer`() {
        assertNull(offer(measured(base, 5.0), base, UILanguage.English))
    }

    @Test
    fun `the offered model is never as heavy as the running one`() {
        for (current in PhoneModelCatalog.all) for (seconds in listOf(1.8, 2.5, 4.0, 9.0, 40.0)) {
            val language = if (current.isHebrewOnly) UILanguage.Hebrew else UILanguage.English
            val chosen = offer(measured(current, seconds), current, language)
            if (chosen != null) assertTrue(chosen.passGflop < current.passGflop, "${current.file} at $seconds s -> ${chosen.file}")
        }
    }

    @Test
    fun `a dismissed model on this phone is not offered again`() {
        val gone = listOf(ModelFitWatch.dismissalKey(small.variant, phone))
        assertNull(offer(measured(turbo, 2.0), turbo, UILanguage.English, dismissed = gone))
    }

    @Test
    fun `a model chosen by hand against the warning is not offered a swap, on this phone only`() {
        val chosen = listOf(ModelFitWatch.dismissalKey(turbo.variant, phone))
        assertNull(offer(measured(turbo, 2.0), turbo, UILanguage.English, dismissed = chosen))
        assertEquals(small.file, offer(measured(turbo, 2.0), turbo, UILanguage.English, dismissed = chosen, device = "Galaxy S10")?.file)
    }

    @Test
    fun `another phone's dismissal does not hide the offer here`() {
        val gone = listOf(ModelFitWatch.dismissalKey(small.variant, "Galaxy S10"))
        assertEquals(small.file, offer(measured(turbo, 2.0), turbo, UILanguage.English, dismissed = gone)?.file)
    }

    @Test
    fun `another model's dismissal does not hide this one`() {
        val gone = listOf(ModelFitWatch.dismissalKey(base.variant, phone))
        assertEquals(small.file, offer(measured(turbo, 2.0), turbo, UILanguage.English, dismissed = gone)?.file)
    }

    @Test
    fun `a dismissal is keyed by model and phone together`() {
        assertTrue(ModelFitWatch.dismissalKey("small", "A") != ModelFitWatch.dismissalKey("small", "B"))
        assertTrue(ModelFitWatch.dismissalKey("small", "A") != ModelFitWatch.dismissalKey("base", "A"))
    }

    @Test
    fun `a measurement of another model says nothing about the running one`() {
        assertNull(offer(measured(turbo, 4.0), small, UILanguage.English))
    }

    @Test
    fun `a model the advisor recommends anyway gets no offer`() {
        val slow = measured(base, 2.4)!!
        assertEquals(PhoneModelFit.ALittleBehind, PhoneModelEstimate.fit(base, slow.gflops))
        assertEquals(base.file, PhoneModelEstimate.recommend(UILanguage.English, slow.gflops, plenty).file)
        assertNull(offer(slow, base, UILanguage.English))
    }

    private fun counted(passes: Int, eligible: Boolean, model: PhoneModel = turbo, seconds: Double = 2.0): LiveMeasurement {
        val gflops = ModelFitWatch.measurement(model, 20, seconds * 20, now = 1.0)!!.gflops
        return LiveMeasurement(model.file, seconds, passes, gflops, 1.0, fitEligible = eligible, device = phone)
    }

    @Test
    fun `nineteen counted passes offer nothing and twenty offer the lighter model`() {
        assertNull(offer(counted(19, true), turbo, UILanguage.English))
        assertEquals(small.file, offer(counted(20, true), turbo, UILanguage.English)?.file)
    }

    @Test
    fun `a timing taken from every pass offers nothing however many passes it has`() {
        assertNull(offer(counted(300, false), turbo, UILanguage.English))
    }

    @Test
    fun `the timing the counted passes save is marked as eligible`() {
        assertTrue(ModelFitWatch.measurement(turbo, 20, 40.0, now = 1.0)!!.fitEligible)
        assertEquals(small.file, offer(ModelFitWatch.measurement(turbo, 20, 40.0, now = 1.0), turbo, UILanguage.English)?.file)
    }

    @Test
    fun `a model that is not on the phone and has no room to download is not offered`() {
        assertTrue(ModelFitWatch.fitsOnPhone(installed = true, shortfallMegabytes = 900))
        assertTrue(ModelFitWatch.fitsOnPhone(installed = false, shortfallMegabytes = null))
        assertFalse(ModelFitWatch.fitsOnPhone(installed = false, shortfallMegabytes = 12))
    }
}
