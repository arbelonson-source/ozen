package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteSizeTest {
    @Test
    fun `kilobytes, megabytes and gigabytes, decimal, as the model list writes them`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("0 KB", ByteSize.text(0))
        assertEquals("1 KB", ByteSize.text(400))
        assertEquals("640 KB", ByteSize.text(640_000))
        assertEquals("999 KB", ByteSize.text(999_000))
        assertEquals("1 MB", ByteSize.text(999_001))
        assertEquals("1 MB", ByteSize.text(1_000_000))
        assertEquals("819 MB", ByteSize.text(819_200_000))
        assertEquals("999 MB", ByteSize.text(999_400_000))
        assertEquals("1.0 GB", ByteSize.text(999_600_000))
        assertEquals("1.6 GB", ByteSize.text(1_619_000_000))
        for (option in WhisperModelCatalog.options) {
            assertEquals(option.sizeLabel, ByteSize.text(option.sizeMB.toLong() * StorageSpaceGate.BYTES_PER_MEGABYTE), option.variant)
        }
    }

    @Test
    fun `French, Russian and Arabic units, and a decimal comma where the language writes one`() {
        val cases = listOf(
            UILanguage.French to listOf("640 ko", "819 Mo", "1,6 Go"),
            UILanguage.German to listOf("640 KB", "819 MB", "1,6 GB"),
            UILanguage.Russian to listOf("640 КБ", "819 МБ", "1,6 ГБ"),
            UILanguage.Ukrainian to listOf("640 КБ", "819 МБ", "1,6 ГБ"),
            UILanguage.Arabic to listOf("640 كيلوبايت", "819 ميغابايت", "1.6 غيغابايت"),
            UILanguage.Hebrew to listOf("640 KB", "819 MB", "1.6 GB"),
        )
        for ((language, expected) in cases) {
            Localization.withLanguage(language) {
                assertEquals(expected, listOf(640_000L, 819_200_000L, 1_619_000_000L).map { ByteSize.text(it) }, "$language")
            }
        }
        Localization.withLanguage(UILanguage.Spanish) {
            assertEquals("1,4 GB", ByteSize.roundedUp(1_301))
            assertEquals("999 MB", ByteSize.roundedUp(999))
        }
        Localization.withLanguage(UILanguage.Portuguese) {
            assertEquals("450 MB", ByteSize.megabytes(450))
        }
    }

    @Test
    fun `the model list's sizes follow the language too`() = Localization.withLanguage(UILanguage.French) {
        for (option in WhisperModelCatalog.options) {
            assertEquals(ByteSize.text(option.sizeMB.toLong() * StorageSpaceGate.BYTES_PER_MEGABYTE), option.sizeLabel, option.variant)
            assertTrue(option.sizeLabel.endsWith("Mo") || option.sizeLabel.endsWith("Go"), option.sizeLabel)
        }
    }

    @Test
    fun `the unit is the one each translation already writes, as in about 120 MB of mobile data an hour`() {
        for (language in UILanguage.entries.filter { it != UILanguage.Hebrew }) {
            Localization.withLanguage(language) {
                val sentence = tr("", "Away from Wi‑Fi it uses about 120 MB of mobile data an hour.")
                val size = ByteSize.megabytes(120).replace(" ", " ")
                assertTrue(sentence.contains(size), "$language: $sentence")
            }
        }
    }

    @Test
    fun `a size never wraps between its number and its unit, at any text size`() {
        for (language in UILanguage.entries) {
            Localization.withLanguage(language) {
                val sizes = listOf(0L, 640_000L, 819_200_000L, 1_619_000_000L).map { ByteSize.text(it) } +
                    WhisperModelCatalog.options.map { it.sizeLabel } + listOf(ByteSize.megabytes(450), ByteSize.roundedUp(1_301))
                for (size in sizes) {
                    assertFalse(size.contains(" "), "$language: $size")
                    assertTrue(size.contains(" "), "$language: $size")
                }
            }
        }
    }
}
