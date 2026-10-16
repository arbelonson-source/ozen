package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneModelChoiceTest {
    private val ozen = PhoneModelCatalog.byFile("ggml-ozen-a3-q5_0.bin")!!
    private val turbo = PhoneModelCatalog.byFile("ggml-large-v3-turbo-q5_0.bin")!!
    private val small = PhoneModelCatalog.byFile("ggml-small-q5_1.bin")!!

    @Test
    fun `a model already on the phone is used at once`() {
        assertEquals(PhoneModelChoice.Plan.UseNow, PhoneModelChoice.plan(small, ozen, setOf(ozen.file, small.file)))
    }

    @Test
    fun `while the model captions run on is on the phone, a model that is not has to arrive before the switch`() {
        assertEquals(PhoneModelChoice.Plan.DownloadFirst, PhoneModelChoice.plan(small, ozen, setOf(ozen.file)))
    }

    @Test
    fun `when the model in use is not on the phone yet, nothing is running on it, so the choice is made at once`() {
        assertEquals(PhoneModelChoice.Plan.UseNow, PhoneModelChoice.plan(small, ozen, emptySet()))
        assertEquals(PhoneModelChoice.Plan.UseNow, PhoneModelChoice.plan(small, ozen, setOf(turbo.file)))
    }

    @Test
    fun `the recommended model is stored as no choice, so the recommendation follows the spoken language`() {
        assertNull(PhoneModelChoice.storedFile(UILanguage.Hebrew, ozen))
        assertNull(PhoneModelChoice.storedFile(UILanguage.English, turbo))
        assertEquals(small.file, PhoneModelChoice.storedFile(UILanguage.English, small))
        assertEquals(turbo.file, PhoneModelChoice.storedFile(UILanguage.Hebrew, turbo))
    }
}
