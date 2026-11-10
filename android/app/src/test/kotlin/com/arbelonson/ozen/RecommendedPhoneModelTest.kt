package com.arbelonson.ozen

import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelEstimate
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecommendedPhoneModelTest {
    @Test
    fun `before a phone test the catalog's recommended model is the one`() {
        for (language in listOf(UILanguage.Hebrew, UILanguage.English)) {
            assertEquals(PhoneModelCatalog.recommended(language), recommendedPhoneModel(language, null, null))
        }
    }

    @Test
    fun `after a phone test the estimate's pick is the one, which a slow phone makes a different model from the catalog's`() {
        val slow = 20.0
        val picked = PhoneModelEstimate.recommend(UILanguage.Hebrew, slow, 4_000_000_000)
        assertNotEquals(PhoneModelCatalog.recommended(UILanguage.Hebrew), picked)
        assertEquals(picked, recommendedPhoneModel(UILanguage.Hebrew, slow, 4_000_000_000))
    }

    @Test
    fun `without a phone test a phone with little memory gets the estimate's pick, not the catalog's`() {
        val memory = 1_500_000_000L
        val picked = PhoneModelEstimate.recommend(UILanguage.Hebrew, null, memory)
        assertNotEquals(PhoneModelCatalog.recommended(UILanguage.Hebrew), picked)
        assertEquals(picked, recommendedPhoneModel(UILanguage.Hebrew, null, memory))
    }
}
