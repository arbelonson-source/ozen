package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneModelShelfTest {
    private val hebrewOnly = PhoneModelCatalog.all.first { it.isHebrewOnly }.file
    private val general = PhoneModelCatalog.all.first { !it.isHebrewOnly }.file

    @Test
    fun `a part file with no model file is a leftover with its size`() {
        val entries = listOf(StoredPhoneModel("$general.part", 77, false), StoredPhoneModel(hebrewOnly, 5, false), StoredPhoneModel("$hebrewOnly.part", 9, false))
        assertEquals(mapOf(general to 77L), PhoneModelShelf.partials(entries))
    }

    @Test
    fun `a Hebrew-only model on the phone is still listed when the language is English`() {
        val listed = PhoneModelShelf.notOffered(UILanguage.English, setOf(hebrewOnly), emptyMap()).map { it.file }
        assertEquals(listOf(hebrewOnly), listed)
        assertTrue(PhoneModelShelf.notOffered(UILanguage.Hebrew, setOf(hebrewOnly), emptyMap()).isEmpty())
    }

    @Test
    fun `a leftover part file of a model not offered is listed too`() {
        val listed = PhoneModelShelf.notOffered(UILanguage.English, emptySet(), mapOf(hebrewOnly to 3L)).map { it.file }
        assertEquals(listOf(hebrewOnly), listed)
    }

    @Test
    fun `delete is offered for a leftover but not while that model downloads or is in use`() {
        val partial = mapOf(general to 5L)
        assertTrue(PhoneModelShelf.canDelete(general, emptySet(), partial, inUse = false, downloading = null))
        assertFalse(PhoneModelShelf.canDelete(general, emptySet(), partial, inUse = false, downloading = general))
        assertTrue(PhoneModelShelf.canDelete(general, emptySet(), partial, inUse = false, downloading = hebrewOnly))
        assertFalse(PhoneModelShelf.canDelete(general, setOf(general), partial, inUse = true, downloading = null))
        assertFalse(PhoneModelShelf.canDelete(general, emptySet(), emptyMap(), inUse = false, downloading = null))
    }
}
