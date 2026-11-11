package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelFitSettingsTest {
    private val phone = "Pixel 7"
    private val plenty = 12_000_000_000L

    private fun behind(): AppSettings {
        val settings = AppSettings.default
        settings.hasCompletedOnboarding = true
        settings.spokenLanguage = UILanguage.English
        settings.engine = TranscriptionEngineKind.WhisperKit
        val turbo = settings.phoneModel
        settings.phoneLiveMeasurement = ModelFitWatch.measurement(turbo, 20, 20 * 2.4, now = 500.0, device = phone)
        return settings
    }

    @Test
    fun `older settings files have no live measurement and no dismissals`() {
        val old = AppSettings.fromJson("""{"engine":"whisperKit","phoneSpeed":{"gflops":50.0,"threads":4,"measuredAt":1.0}}""")
        assertNull(old.phoneLiveMeasurement)
        assertEquals(emptyList(), old.modelFitDismissed)
        assertEquals(50.0, old.effectiveGflops)
    }

    @Test
    fun `the live measurement and the dismissals survive saving and loading`() {
        val settings = behind()
        settings.modelFitDismissed = listOf(ModelFitWatch.dismissalKey("small", phone))
        val loaded = AppSettings.fromJson(settings.toJson())
        assertEquals(settings.phoneLiveMeasurement, loaded.phoneLiveMeasurement)
        assertEquals(settings.modelFitDismissed, loaded.modelFitDismissed)
        assertEquals(settings, loaded)
        assertEquals(settings, settings.copy())
    }

    @Test
    fun `a saved measurement that is not usable is dropped when loading`() {
        val bad = AppSettings.fromJson("""{"phoneLiveMeasurement":{"file":"a.bin","averageSeconds":-1.0,"passes":20,"gflops":5.0,"measuredAt":1.0},"modelFitDismissed":"x"}""")
        assertNull(bad.phoneLiveMeasurement)
        assertEquals(emptyList(), bad.modelFitDismissed)
    }

    @Test
    fun `the speed Settings judges by is the newer of the phone test and the live measurement`() {
        val settings = behind()
        val live = settings.phoneLiveMeasurement!!
        settings.phoneSpeed = PhoneSpeed(900.0, 4, live.measuredAt - 1)
        assertEquals(live.gflops, settings.effectiveGflops)
        settings.phoneSpeed = PhoneSpeed(900.0, 4, live.measuredAt + 1)
        assertEquals(900.0, settings.effectiveGflops)
        settings.phoneSpeed = null
        assertEquals(live.gflops, settings.effectiveGflops)
        settings.phoneLiveMeasurement = null
        assertNull(settings.effectiveGflops)
    }

    @Test
    fun `a model that falls behind in use is offered a lighter one on the caption screen`() {
        val offered = behind().modelFitOffer(plenty, phone) { true }
        assertTrue(offered != null && offered.passGflop < behind().phoneModel.passGflop)
    }

    @Test
    fun `no offer during the walkthrough or when the phone is not the engine in use`() {
        val early = behind().also { it.hasCompletedOnboarding = false }
        assertNull(early.modelFitOffer(plenty, phone) { true })
        val cloud = behind().also { it.engine = TranscriptionEngineKind.Cloud }
        assertNull(cloud.modelFitOffer(plenty, phone) { true })
    }

    @Test
    fun `not now hides that model on this phone for good`() {
        val settings = behind()
        val offered = settings.modelFitOffer(plenty, phone) { true }!!
        settings.dismissModelFit(offered.variant, phone)
        assertNull(settings.modelFitOffer(plenty, phone) { true })
        settings.phoneLiveMeasurement = settings.phoneLiveMeasurement!!.copy(device = "Galaxy S10")
        assertTrue(settings.modelFitOffer(plenty, "Galaxy S10") { true } != null)
        settings.dismissModelFit(offered.variant, phone)
        assertEquals(1, settings.modelFitDismissed.size)
    }

    @Test
    fun `a heavy model dismissed on this phone, as choosing it by hand does, is not offered a swap`() {
        val settings = behind()
        settings.dismissModelFit(settings.phoneModel.variant, phone)
        assertNull(settings.modelFitOffer(plenty, phone) { true })
    }

    @Test
    fun `an old settings file with a measurement but no eligible flag loads and offers nothing`() {
        val old = AppSettings.fromJson("""{"engine":"whisperKit","hasCompletedOnboarding":true,"spokenLanguage":"english","phoneLiveMeasurement":{"file":"ggml-large-v3-turbo-q5_0.bin","averageSeconds":2.4,"passes":300,"gflops":40.0,"measuredAt":1.0}}""")
        val loaded = old.phoneLiveMeasurement!!
        assertEquals(false, loaded.fitEligible)
        assertEquals(null, loaded.device)
        assertNull(old.modelFitOffer(plenty, phone) { true })
    }

    @Test
    fun `the eligible flag and the phone survive saving and loading`() {
        val loaded = AppSettings.fromJson(behind().toJson()).phoneLiveMeasurement!!
        assertEquals(true, loaded.fitEligible)
        assertEquals(phone, loaded.device)
    }

    @Test
    fun `a measurement from another phone offers nothing here and not now records nothing`() {
        val settings = behind()
        assertNull(settings.modelFitOffer(plenty, "Galaxy S10") { true })
        settings.dismissModelFitOffer("small", "Galaxy S10")
        assertEquals(emptyList(), settings.modelFitDismissed)
        settings.dismissModelFitOffer("small", phone)
        assertEquals(listOf(ModelFitWatch.dismissalKey("small", phone)), settings.modelFitDismissed)
    }

    @Test
    fun `the saved Apple engine value runs the phone's own model, so it is offered too`() {
        val settings = behind().also { it.engine = TranscriptionEngineKind.AppleSpeech }
        assertTrue(settings.modelFitOffer(plenty, phone) { true } != null)
    }

    @Test
    fun `a model the phone cannot hold is not offered`() {
        assertNull(behind().modelFitOffer(plenty, phone) { false })
    }

    @Test
    fun `the speed for a hand-pick warning ignores a check made on another phone`() {
        val settings = behind()
        settings.phoneSpeed = PhoneSpeed(900.0, 4, 600.0, device = "Galaxy S10")
        assertEquals(settings.phoneLiveMeasurement!!.gflops, settings.gflopsOn(phone))
        settings.phoneSpeed = PhoneSpeed(900.0, 4, 600.0, device = phone)
        assertEquals(900.0, settings.gflopsOn(phone))
        settings.phoneLiveMeasurement = null
        assertNull(settings.gflopsOn("Galaxy S10"))
    }

    @Test
    fun `a phone test counts as this phone's only when taken on it or before phones were recorded`() {
        val settings = behind()
        settings.phoneSpeed = null
        assertNull(settings.phoneSpeedOn(phone))
        val elsewhere = PhoneSpeed(900.0, 4, 600.0, device = "Galaxy S10")
        settings.phoneSpeed = elsewhere
        assertNull(settings.phoneSpeedOn(phone))
        assertEquals(elsewhere, settings.phoneSpeedOn("Galaxy S10"))
        val unrecorded = PhoneSpeed(900.0, 4, 600.0)
        settings.phoneSpeed = unrecorded
        assertEquals(unrecorded, settings.phoneSpeedOn(phone))
    }

    @Test
    fun `the battery saver flag survives saving and loading and an old file reads false`() {
        val settings = behind()
        settings.phoneLiveMeasurement = ModelFitWatch.measurement(settings.phoneModel, 20, 48.0, now = 500.0, device = phone, lowPower = true)
        val loaded = AppSettings.fromJson(settings.toJson())
        assertEquals(true, loaded.phoneLiveMeasurement!!.lowPower)
        assertEquals(settings, loaded)
        assertEquals(false, AppSettings.fromJson(behind().toJson()).phoneLiveMeasurement!!.lowPower)
        val old = AppSettings.fromJson("""{"phoneLiveMeasurement":{"file":"a.bin","averageSeconds":2.4,"passes":20,"gflops":5.0,"measuredAt":1.0}}""")
        assertEquals(false, old.phoneLiveMeasurement!!.lowPower)
    }

    @Test
    fun `a measurement outside battery saver writes no battery saver key`() {
        assertEquals(false, behind().phoneLiveMeasurement!!.toJsonObject().containsKey("lowPower"))
        val saver = behind().phoneLiveMeasurement!!.copy(lowPower = true)
        assertEquals(true, saver.toJsonObject().containsKey("lowPower"))
    }

    @Test
    fun `a battery saver measurement gets the same offer and labels as a normal one`() {
        val settings = behind()
        val normalOffer = settings.modelFitOffer(plenty, phone) { true }
        settings.phoneLiveMeasurement = settings.phoneLiveMeasurement!!.copy(lowPower = true)
        assertEquals(normalOffer, settings.modelFitOffer(plenty, phone) { true })
        assertEquals(settings.phoneLiveMeasurement!!.gflops, settings.effectiveGflops)
    }
}
