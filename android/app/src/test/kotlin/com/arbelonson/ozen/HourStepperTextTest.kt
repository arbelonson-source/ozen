package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HourStepperTextTest {
    @Test
    fun `a step button is named by the sign on it first, then the row it moves`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("+ Start: one hour later", HourStepperText.button(later = true, row = HourStepperText.start()))
        assertEquals("− Start: one hour earlier", HourStepperText.button(later = false, row = HourStepperText.start()))
        assertEquals("+ End: one hour later", HourStepperText.button(later = true, row = HourStepperText.end()))
        assertEquals("− End: one hour earlier", HourStepperText.button(later = false, row = HourStepperText.end()))
    }

    @Test
    fun `the four buttons of the two rows all have different names`() = Localization.withLanguage(UILanguage.Hebrew) {
        val names = listOf(HourStepperText.start(), HourStepperText.end()).flatMap { row ->
            listOf(true, false).map { HourStepperText.button(it, row) }
        }
        assertEquals(4, names.toSet().size)
        assertTrue(names.all { it.startsWith("+ ") || it.startsWith("− ") })
        assertNotEquals(names[0], names[2])
    }
}
