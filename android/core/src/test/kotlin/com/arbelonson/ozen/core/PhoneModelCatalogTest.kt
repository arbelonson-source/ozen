package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhoneModelCatalogTest {
    private val ozenModels = Regex("https://github\\.com/arbelonson-source/ozen-models/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")
    private val huggingFace = Regex("https://huggingface\\.co/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/resolve/[0-9a-f]{40}/[A-Za-z0-9._-]+")

    @Test
    fun `every model has a file, a size, a checksum and a download from a host the app already uses, pinned to one release or revision`() {
        assertTrue(PhoneModelCatalog.all.size >= 5)
        for (model in PhoneModelCatalog.all) {
            assertTrue(model.file.endsWith(".bin"), model.file)
            assertTrue(model.source.bytes > 1_000_000, model.file)
            assertTrue(Regex("[0-9a-f]{64}").matches(model.source.sha256), model.file)
            val url = model.source.url.toString()
            assertTrue(ozenModels.matches(url) || huggingFace.matches(url), url)
            assertTrue(model.passGflop > 0, model.file)
            assertTrue(model.hebrewQuality in 1..5, model.file)
            assertTrue(model.isHebrewOnly || model.otherQuality in 1..5, model.file)
        }
    }

    @Test
    fun `no two models share a file name or a checksum`() {
        assertEquals(PhoneModelCatalog.all.size, PhoneModelCatalog.all.map { it.file }.toSet().size)
        assertEquals(PhoneModelCatalog.all.size, PhoneModelCatalog.all.map { it.source.sha256 }.toSet().size)
    }

    @Test
    fun `the catalog holds Ozen's Hebrew model, ivrit ai's Turbo Hebrew, the general Turbo and a smaller and a lighter general one`() {
        val files = PhoneModelCatalog.all.map { it.file }
        assertTrue("ggml-ozen-a3-q5_0.bin" in files)
        assertTrue("ggml-large-v3-turbo-q5_0.bin" in files)
        assertTrue(PhoneModelCatalog.all.any { it.source.url.toString().contains("/ivrit-ai/whisper-large-v3-turbo-ggml/") })
        assertTrue(PhoneModelCatalog.all.any { it.file.contains("small") })
        assertTrue(PhoneModelCatalog.all.any { it.file.contains("base") })
        assertEquals(setOf("ggml-ozen-a3-q5_0.bin", "ggml-ivrit-large-v3-turbo.bin"), PhoneModelCatalog.all.filter { it.isHebrewOnly }.map { it.file }.toSet())
    }

    @Test
    fun `the recommended model is Ozen's for Hebrew and the general Turbo for every other language, and is listed first`() {
        for (language in UILanguage.entries) {
            val expected = if (language == UILanguage.Hebrew) "ggml-ozen-a3-q5_0.bin" else "ggml-large-v3-turbo-q5_0.bin"
            assertEquals(expected, PhoneModelCatalog.recommended(language).file)
            assertEquals(expected, PhoneModelCatalog.offered(language).first().file)
            assertEquals(WhisperModelCatalog.phoneModelFile(language), expected)
        }
    }

    @Test
    fun `a language other than Hebrew is not offered the Hebrew only models`() {
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            assertTrue(PhoneModelCatalog.offered(language).none { it.isHebrewOnly })
            assertTrue(PhoneModelCatalog.offered(language).size >= 3)
        }
        assertEquals(PhoneModelCatalog.all.size, PhoneModelCatalog.offered(UILanguage.Hebrew).size)
    }

    @Test
    fun `after the recommended one the models come most accurate first`() {
        for (language in UILanguage.entries) {
            val rest = PhoneModelCatalog.offered(language).drop(1).map { it.quality(language) }
            assertEquals(rest.sortedDescending(), rest)
        }
    }

    @Test
    fun `a saved choice is used for a language that offers it and the recommended one otherwise`() {
        assertEquals("ggml-small-q5_1.bin", PhoneModelCatalog.effective(UILanguage.Hebrew, "ggml-small-q5_1.bin").file)
        assertEquals("ggml-small-q5_1.bin", PhoneModelCatalog.effective(UILanguage.English, "ggml-small-q5_1.bin").file)
        assertEquals("ggml-ozen-a3-q5_0.bin", PhoneModelCatalog.effective(UILanguage.Hebrew, null).file)
        assertEquals("ggml-ozen-a3-q5_0.bin", PhoneModelCatalog.effective(UILanguage.Hebrew, "ggml-gone.bin").file)
        assertEquals("ggml-large-v3-turbo-q5_0.bin", PhoneModelCatalog.effective(UILanguage.English, "ggml-ozen-a3-q5_0.bin").file)
        assertEquals("ggml-ozen-a3-q5_0.bin", PhoneModelCatalog.effective(UILanguage.Hebrew, "ggml-ozen-a3-q5_0.bin").file)
    }

    @Test
    fun `the storage screen manages every catalog file, so a chosen model can be freed too`() {
        assertEquals(PhoneModelCatalog.all.map { it.file }.toSet(), PhoneModelCatalog.managedFiles)
        val root = java.nio.file.Files.createTempDirectory("catalog-files").toFile()
        try {
            for (model in PhoneModelCatalog.all) java.io.File(root, model.file).writeBytes(ByteArray(3))
            val listed = PhoneModelStorage(root, "ggml-ozen-a3-q5_0.bin").list().map { it.name }.toSet()
            assertEquals(PhoneModelCatalog.managedFiles, listed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `each model names the variant its confidence cutoffs follow, and the Hebrew ones differ from the general one`() {
        val variants = PhoneModelCatalog.all.map { it.variant }
        assertEquals(variants.size, variants.toSet().size)
        assertNotEquals(PhoneModelCatalog.recommended(UILanguage.Hebrew).variant, PhoneModelCatalog.recommended(UILanguage.English).variant)
        assertFalse(PhoneModelCatalog.all.any { it.displayName.isBlank() || it.note.isBlank() })
    }
}
