package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelLoadCrashTest {
    private val ivrit = PhoneModelCatalog.byFile("ggml-ivrit-large-v3-turbo.bin")!!
    private val turbo = PhoneModelCatalog.byFile(PhoneModelCatalog.GENERAL_FILE)!!
    private val ozen = PhoneModelCatalog.byFile(PhoneModelCatalog.OZEN_HEBREW_FILE)!!
    private val small = PhoneModelCatalog.byFile("ggml-small-q5_1.bin")!!
    private val base = PhoneModelCatalog.byFile("ggml-base-q5_1.bin")!!
    private val gigabyte = 1_000_000_000L

    @Test
    fun `in Hebrew the heaviest model is offered the Ozen model when the memory is unknown`() {
        assertEquals(ozen, ModelLoadCrash.lighter(ivrit, UILanguage.Hebrew, null))
    }

    @Test
    fun `a lighter model that does not fit the memory loses to one that does`() {
        assertEquals(turbo, ModelLoadCrash.lighter(ivrit, UILanguage.English, 2 * gigabyte))
        assertEquals(small, ModelLoadCrash.lighter(ivrit, UILanguage.English, 1 * gigabyte))
        assertEquals(ozen, ModelLoadCrash.lighter(ivrit, UILanguage.Hebrew, 2 * gigabyte))
        assertEquals(small, ModelLoadCrash.lighter(ivrit, UILanguage.Hebrew, 1 * gigabyte))
    }

    @Test
    fun `when none fits the memory the best lighter model is still offered`() {
        assertEquals(ozen, ModelLoadCrash.lighter(ivrit, UILanguage.Hebrew, 100_000_000L))
    }

    @Test
    fun `the lightest model has nothing lighter to offer`() {
        assertNull(ModelLoadCrash.lighter(base, UILanguage.English, null))
        assertNull(ModelLoadCrash.lighter(base, UILanguage.Hebrew, 8 * gigabyte))
    }

    @Test
    fun `a model of the same size is not lighter`() {
        assertEquals(small, ModelLoadCrash.lighter(ozen, UILanguage.Hebrew, null))
        assertEquals(small, ModelLoadCrash.lighter(turbo, UILanguage.English, null))
    }

    @Test
    fun `models of equal quality go to the one with fewer bytes`() {
        val bigger = small.copy(file = "a.bin", source = small.source.copy(bytes = 200_000_000))
        val smaller = small.copy(file = "b.bin", source = small.source.copy(bytes = 100_000_000))
        assertEquals(smaller, ModelLoadCrash.lighter(ivrit, UILanguage.English, null, listOf(bigger, smaller)))
        assertEquals(smaller, ModelLoadCrash.lighter(ivrit, UILanguage.English, null, listOf(smaller, bigger)))
    }

    @Test
    fun `a mark that names the current model gives an offer and another file or no mark gives none`() {
        assertEquals(ozen, ModelLoadCrash.offer(ModelLoadCrash.marker(ivrit.file), ivrit, UILanguage.Hebrew, null))
        assertNull(ModelLoadCrash.offer(ModelLoadCrash.marker(small.file), ivrit, UILanguage.Hebrew, null))
        assertNull(ModelLoadCrash.offer(null, ivrit, UILanguage.Hebrew, null))
    }
}
