package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneModelSettingsTest {
    private fun roundTripped(settings: AppSettings) = AppSettings.fromJson(settings.toJson())

    @Test
    fun `nobody has chosen a phone model or tested the phone until they do, and older settings files read the same`() {
        assertNull(AppSettings.default.phoneModelFile)
        assertNull(AppSettings.default.phoneSpeed)
        val old = AppSettings.fromJson("""{"engine":"whisperKit"}""")
        assertNull(old.phoneModelFile)
        assertNull(old.phoneSpeed)
    }

    @Test
    fun `the chosen phone model and the phone test result survive saving and loading`() {
        val settings = AppSettings.default
        settings.phoneModelFile = "ggml-small-q5_1.bin"
        settings.phoneSpeed = PhoneSpeed(gflops = 87.5, threads = 4, measuredAt = 1_760_000_000.0)
        val loaded = roundTripped(settings)
        assertEquals("ggml-small-q5_1.bin", loaded.phoneModelFile)
        assertEquals(PhoneSpeed(87.5, 4, 1_760_000_000.0), loaded.phoneSpeed)
        assertEquals(settings, loaded)
    }

    @Test
    fun `copying the settings keeps the choice, and a different choice makes them different`() {
        val settings = AppSettings.default
        settings.phoneModelFile = "ggml-base-q5_1.bin"
        settings.phoneSpeed = PhoneSpeed(12.0, 2, 5.0)
        val copy = settings.copy()
        assertEquals(settings, copy)
        copy.phoneModelFile = null
        assertTrue(settings != copy)
        val other = settings.copy()
        other.phoneSpeed = PhoneSpeed(13.0, 2, 5.0)
        assertTrue(settings != other)
    }

    @Test
    fun `a saved speed that is not a positive number is dropped when loading`() {
        val bad = AppSettings.fromJson("""{"phoneSpeed":{"gflops":-3.0,"threads":4,"measuredAt":1.0}}""")
        assertNull(bad.phoneSpeed)
        val garbage = AppSettings.fromJson("""{"phoneSpeed":"fast","phoneModelFile":7}""")
        assertNull(garbage.phoneSpeed)
        assertNull(garbage.phoneModelFile)
    }

    @Test
    fun `the model the phone runs is the saved choice for the spoken language, or the recommended one`() {
        val settings = AppSettings.default
        settings.spokenLanguage = UILanguage.English
        assertEquals("ggml-large-v3-turbo-q5_0.bin", settings.phoneModel.file)
        settings.phoneModelFile = "ggml-base-q5_1.bin"
        assertEquals("ggml-base-q5_1.bin", settings.phoneModel.file)
        settings.phoneModelFile = "ggml-ozen-a3-q5_0.bin"
        assertEquals("ggml-large-v3-turbo-q5_0.bin", settings.phoneModel.file)
        settings.spokenLanguage = UILanguage.Hebrew
        assertEquals("ggml-ozen-a3-q5_0.bin", settings.phoneModel.file)
    }
}
