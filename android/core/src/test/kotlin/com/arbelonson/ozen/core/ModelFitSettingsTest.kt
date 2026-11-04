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
        settings.phoneLiveMeasurement = ModelFitWatch.measurement(turbo, 20, 20 * 2.4, now = 500.0)
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
        val offered = behind().modelFitOffer(plenty, phone)
        assertTrue(offered != null && offered.passGflop < behind().phoneModel.passGflop)
    }

    @Test
    fun `no offer during the walkthrough or when the phone is not the engine in use`() {
        val early = behind().also { it.hasCompletedOnboarding = false }
        assertNull(early.modelFitOffer(plenty, phone))
        val cloud = behind().also { it.engine = TranscriptionEngineKind.Cloud }
        assertNull(cloud.modelFitOffer(plenty, phone))
    }

    @Test
    fun `not now hides that model on this phone for good`() {
        val settings = behind()
        val offered = settings.modelFitOffer(plenty, phone)!!
        settings.dismissModelFit(offered.variant, phone)
        assertNull(settings.modelFitOffer(plenty, phone))
        assertTrue(settings.modelFitOffer(plenty, "Galaxy S10") != null)
        settings.dismissModelFit(offered.variant, phone)
        assertEquals(1, settings.modelFitDismissed.size)
    }

    @Test
    fun `a heavy model dismissed on this phone, as choosing it by hand does, is not offered a swap`() {
        val settings = behind()
        settings.dismissModelFit(settings.phoneModel.variant, phone)
        assertNull(settings.modelFitOffer(plenty, phone))
    }
}
