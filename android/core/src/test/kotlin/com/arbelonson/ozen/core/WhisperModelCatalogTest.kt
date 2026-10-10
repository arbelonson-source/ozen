package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhisperModelCatalogTest {
    @Test
    fun `the default and recommended variants both exist in the catalog`() {
        assertNotNull(WhisperModelCatalog.option(WhisperModelCatalog.DEFAULT_VARIANT))
        assertEquals(true, WhisperModelCatalog.option(WhisperModelCatalog.RECOMMENDED_VARIANT)?.isRecommended)
        assertEquals(WhisperModelCatalog.DEFAULT_VARIANT, AppSettings.default.whisperModelVariant)
    }

    @Test
    fun `a fresh install gets the recommended model, not Small`() {
        assertEquals(WhisperModelCatalog.RECOMMENDED_VARIANT, WhisperModelCatalog.DEFAULT_VARIANT)
        assertEquals("ozen-turbo-hebrew-a3-8bit", AppSettings.default.whisperModelVariant)
    }

    @Test
    fun `the recommended model improves on the small ones, not on its equals or betters or on strangers`() {
        for (weaker in listOf("tiny", "base", "small_216MB", "small", "medium", "large-v3-v20240930_626MB", "large-v3-v20240930", "large-v3_947MB", "large-v3")) {
            assertTrue(WhisperModelCatalog.recommendedImproves(weaker), weaker)
        }
        assertFalse(WhisperModelCatalog.recommendedImproves(WhisperModelCatalog.RECOMMENDED_VARIANT))
        // What it was trained from: better in noise, level up close, so no
        // "this model gets many words wrong" on a phone that runs it.
        assertFalse(WhisperModelCatalog.recommendedImproves("ivrit-large-v3-turbo-8bit"))
        assertFalse(WhisperModelCatalog.recommendedImproves("no-such-model"))
    }

    @Test
    fun `no model is rated above the recommended one for Hebrew, and the only one rated level is the model it was trained from`() {
        val recommended = assertNotNull(WhisperModelCatalog.option(WhisperModelCatalog.RECOMMENDED_VARIANT))
        for (option in WhisperModelCatalog.options.filter { it.variant != recommended.variant }) {
            if (option.variant == "ivrit-large-v3-turbo-8bit") {
                assertEquals(recommended.hebrewQuality, option.hebrewQuality)
            } else {
                assertTrue(option.hebrewQuality < recommended.hebrewQuality, option.variant)
            }
        }
    }

    @Test
    fun `exactly one option is recommended and variants are unique`() {
        assertEquals(1, WhisperModelCatalog.options.count { it.isRecommended })
        val variants = WhisperModelCatalog.options.map { it.variant }
        assertEquals(variants.size, variants.toSet().size)
    }

    @Test
    fun `ratings stay in the 1 to 5 range and no English-only model slipped in`() {
        for (option in WhisperModelCatalog.options) {
            assertTrue(option.hebrewQuality in 1..5, option.variant)
            assertTrue(option.speed in 1..5, option.variant)
            assertFalse(option.variant.contains(".en"), option.variant)
            assertFalse(option.variant.contains("distil"), option.variant)
            assertTrue(option.sizeMB > 0)
        }
    }

    @Test
    fun `folder names round-trip`() {
        assertEquals("openai_whisper-small", WhisperModelCatalog.folderName("small"))
        assertEquals("ivrit-ai_whisper-large-v3-turbo_8bit", WhisperModelCatalog.folderName("ivrit-large-v3-turbo-8bit"))
        assertEquals("ivrit-large-v3-turbo-8bit", WhisperModelCatalog.variantFromFolderName("ivrit-ai_whisper-large-v3-turbo_8bit"))
        assertEquals(WhisperModelSource.OzenRelease(tag = "model-ivrit-large-v3-turbo-8bit-1"), WhisperModelCatalog.option("ivrit-large-v3-turbo-8bit")?.source)
        assertEquals(WhisperModelSource.OzenRelease(tag = "model-ozen-turbo-hebrew-a3-8bit-1"), WhisperModelCatalog.option(WhisperModelCatalog.RECOMMENDED_VARIANT)?.source)
        assertEquals("ozen_whisper-large-v3-turbo-hebrew-a3_8bit", WhisperModelCatalog.folderName("ozen-turbo-hebrew-a3-8bit"))
        assertEquals("ozen-turbo-hebrew-a3-8bit", WhisperModelCatalog.variantFromFolderName("ozen_whisper-large-v3-turbo-hebrew-a3_8bit"))
        assertEquals("large-v3-v20240930_626MB", WhisperModelCatalog.variantFromFolderName("openai_whisper-large-v3-v20240930_626MB"))
        assertEquals("unlisted-test", WhisperModelCatalog.variantFromFolderName("openai_whisper-unlisted-test"))
        assertNull(WhisperModelCatalog.variantFromFolderName("someone_whisper-unlisted-test"))
        assertNull(WhisperModelCatalog.variantFromFolderName("distil-whisper_distil-large-v3"))
    }

    @Test
    fun `a model compiled on the phone needs room for the packages and the compiled bundles together`() {
        assertEquals(486, WhisperModelCatalog.option("small")?.installMegabytes)
        assertEquals(819 * 2, WhisperModelCatalog.option("ivrit-large-v3-turbo-8bit")?.installMegabytes)
        assertEquals(819 * 2, WhisperModelCatalog.option("ozen-turbo-hebrew-a3-8bit")?.installMegabytes)
    }

    @Test
    fun `an interrupted install only needs room for the part not yet on the phone`() {
        val ivrit = assertNotNull(WhisperModelCatalog.option("ivrit-large-v3-turbo-8bit"))
        val megabyte = 1_000_000L
        assertEquals(819 * 2, ivrit.remainingInstallMegabytes(onDiskBytes = 0))
        assertEquals(819, ivrit.remainingInstallMegabytes(onDiskBytes = 819 * megabyte))
        assertEquals(1, ivrit.remainingInstallMegabytes(onDiskBytes = 5_000 * megabyte))
    }

    @Test
    fun `the model screen lists the recommended model first and every other in the catalog's order`() {
        val listed = WhisperModelCatalog.listed.map { it.variant }
        assertEquals(WhisperModelCatalog.RECOMMENDED_VARIANT, listed.firstOrNull())
        assertEquals(
            WhisperModelCatalog.options.map { it.variant }.filter { it != WhisperModelCatalog.RECOMMENDED_VARIANT },
            listed.drop(1),
        )
    }

    @Test
    fun `with the whole download on the phone, only the compile's room and none of the download is left`() {
        val ivrit = assertNotNull(WhisperModelCatalog.option("ivrit-large-v3-turbo-8bit"))
        val releaseBytes = 818_952_412L
        assertTrue(abs(ivrit.remainingInstallMegabytes(onDiskBytes = releaseBytes) - ivrit.sizeMB) <= 1)
        assertEquals(1, ivrit.remainingDownloadMegabytes(onDiskBytes = releaseBytes))
        assertEquals(819, ivrit.remainingDownloadMegabytes(onDiskBytes = 0))
    }

    @Test
    fun `size labels switch to GB at a thousand megabytes`() {
        assertEquals("486 MB", WhisperModelCatalog.option("small")?.sizeLabel)
        assertEquals("3.1 GB", WhisperModelCatalog.option("large-v3")?.sizeLabel)
    }
}
