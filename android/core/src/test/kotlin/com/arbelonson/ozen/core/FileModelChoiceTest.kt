package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileModelChoiceTest {
    private val ozen = PhoneModelCatalog.OZEN_HEBREW_FILE
    private val turbo = PhoneModelCatalog.GENERAL_FILE
    private val small = "ggml-small-q5_1.bin"
    private val base = "ggml-base-q5_1.bin"
    private val ivrit = "ggml-ivrit-large-v3-turbo.bin"

    @Test
    fun hebrewTakesTheOzenModelWhenItIsOnThePhone() {
        assertEquals(ozen, FileModelChoice.pick(UILanguage.Hebrew, setOf(base, turbo, ozen, small))?.file)
    }

    @Test
    fun hebrewWithoutTheOzenModelTakesTheMostAccurateOneThatIsThere() {
        assertEquals(turbo, FileModelChoice.pick(UILanguage.Hebrew, setOf(base, turbo, small))?.file)
        assertEquals(small, FileModelChoice.pick(UILanguage.Hebrew, setOf(base, small))?.file)
    }

    @Test
    fun theOzenModelWinsATieWithTheOtherHebrewModel() {
        assertEquals(ozen, FileModelChoice.pick(UILanguage.Hebrew, setOf(ivrit, ozen))?.file)
        assertEquals(ivrit, FileModelChoice.pick(UILanguage.Hebrew, setOf(ivrit, turbo))?.file)
    }

    @Test
    fun anotherLanguageNeverGetsAHebrewOnlyModel() {
        assertEquals(turbo, FileModelChoice.pick(UILanguage.English, setOf(ozen, ivrit, turbo, small))?.file)
        assertNull(FileModelChoice.pick(UILanguage.English, setOf(ozen, ivrit)))
    }

    @Test
    fun nothingOnThePhoneGivesNoChoice() {
        assertNull(FileModelChoice.pick(UILanguage.Hebrew, emptySet()))
        assertNull(FileModelChoice.pick(UILanguage.Hebrew, setOf("something-else.bin")))
    }

    @Test
    fun aModelTooBigForThePhonesMemoryIsPassedOverForASmallerOne() {
        val twoGigabytes = 2L * 1024 * 1024 * 1024
        assertEquals(small, FileModelChoice.pick(UILanguage.Hebrew, setOf(ivrit, small), totalMemoryBytes = twoGigabytes)?.file)
        assertEquals(ivrit, FileModelChoice.pick(UILanguage.Hebrew, setOf(ivrit), totalMemoryBytes = twoGigabytes)?.file)
    }

    @Test
    fun theSwitchListHoldsOnlyDownloadedModelsBestFirst() {
        val files = FileModelChoice.downloaded(UILanguage.Hebrew, setOf(base, small, ozen, turbo, "other.bin")).map { it.file }
        assertEquals(listOf(ozen, turbo, small, base), files)
    }

    @Test
    fun whenNothingSuitsTheNormalDownloadIsOffered() {
        assertEquals(ozen, FileModelChoice.toDownload(UILanguage.Hebrew).file)
        assertEquals(turbo, FileModelChoice.toDownload(UILanguage.English).file)
    }
}
