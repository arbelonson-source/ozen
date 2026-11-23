package com.arbelonson.ozen.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NumberWordStartTest {
    private val profiles = UILanguage.entries.mapNotNull { NumberWordProfile.profile(it.languageCode) }.distinct()

    @Test
    fun `a word a number starts at is looked at however it is written`() {
        var looked = 0
        for (profile in profiles) {
            val starts = profile.numbers + profile.onceWords + profile.clockOrdinals + profile.fractions
            for (word in starts) {
                val written = listOf(word, word.uppercase(Locale.ROOT), word.replaceFirstChar { it.titlecase(Locale.ROOT) }, word.replace('\'', '’'))
                for (form in written) {
                    if (profile.normalize(form) !in starts) continue
                    assertTrue(profile.mayStartANumber(" $form.", 1, form.length + 1), "\"$form\"")
                    looked++
                }
            }
        }
        assertTrue(looked > 2000, "only $looked forms were looked at")
    }

    @Test
    fun `a word of characters that stand alone normalizes as each of them is made lower case`() {
        val english = NumberWordProfile.profile("en")!!
        val alone = (0..0xFFFF).map { it.toChar() }.filter { Graphemes.standsAlone(it) && it !in NumberWordProfile.OTHERWISE_NORMALIZED }
        val different = alone.filter {
            val lower = Character.toLowerCase(it)
            english.normalize(it.toString()) != (if (lower == '’') "'" else lower.toString())
        }
        assertEquals(emptyList(), different.map { "U+%04X".format(it.code) })
        assertTrue(alone.size > 20_000)
    }

    @Test
    fun `a word that starts no number is passed over, unless its letters are folded`() {
        val english = NumberWordProfile.profile("en")!!
        for (word in listOf("tomorrow", "House", "someone", "TONE")) assertFalse(english.mayStartANumber(word, 0, word.length), word)
        for (word in listOf("Three", "TWELVE", "half", "Once")) assertTrue(english.mayStartANumber(word, 0, word.length), word)
        assertTrue(NumberWordProfile.profile("ar")!!.mayStartANumber("مرحبا", 0, 5))
    }
}
