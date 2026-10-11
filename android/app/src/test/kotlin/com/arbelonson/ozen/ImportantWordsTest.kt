package com.arbelonson.ozen

import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportantWordsTest {
    private val grandma = KeywordAlert(phrase = "סבתא")

    @Test
    fun `a word goes at the end, and one already listed is turned back on instead of listed twice`() {
        var alerts = ImportantWords.adding("  Shira ", listOf(grandma))
        assertEquals(listOf("סבתא", "Shira"), alerts.map { it.phrase })
        assertEquals(alerts, ImportantWords.adding("shira!", alerts))
        assertEquals(alerts, ImportantWords.adding("   ", alerts))

        alerts = ImportantWords.switched(grandma.id, false, alerts)
        assertEquals(listOf(false, true), alerts.map { it.isEnabled })
        assertTrue(grandma.isEnabled)
        assertEquals(grandma.id, ImportantWords.listed("סבתא", alerts)?.id)

        alerts = ImportantWords.adding("סבתא", alerts)
        assertEquals(listOf(true, true), alerts.map { it.isEnabled })
        assertEquals(listOf("Shira"), ImportantWords.removing(grandma.id, alerts).map { it.phrase })
    }

    @Test
    fun `suggestions are her own words first, then the usual ones, none listed or repeated, at most eight`() {
        val vocabulary = listOf("Shira", "אמא", "Dr. Levi", "shira", "Noa", "Avi", "Dana", "Yoni", "Tal")
        assertEquals(listOf("Shira", "אמא", "Dr. Levi", "Noa", "Avi", "Dana", "Yoni", "Tal"), ImportantWords.suggestions(vocabulary, emptyList(), UILanguage.Hebrew))
        assertEquals(listOf("אמא", "תרופה", "רופא"), ImportantWords.suggestions(emptyList(), listOf(grandma), UILanguage.Hebrew))
    }

    @Test
    fun `for another spoken language the usual suggestions are the words as said in it, none already listed`() {
        assertEquals(listOf("Shira", "Grandma", "Mom", "Medicine", "Doctor"), ImportantWords.suggestions(listOf("Shira"), emptyList(), UILanguage.English))
        assertEquals(listOf("Mom", "Medicine", "Doctor"), ImportantWords.suggestions(emptyList(), listOf(KeywordAlert(phrase = "grandma")), UILanguage.English))
    }
}
