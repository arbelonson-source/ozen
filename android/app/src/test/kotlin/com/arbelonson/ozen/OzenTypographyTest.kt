package com.arbelonson.ozen

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OzenTypographyTest {
    @Test
    fun smallBodyTextIsAtLeastFourteen() {
        val small = OzenTypography.of(Typography()).bodySmall
        assertTrue(small.fontSize >= 14.sp, "bodySmall is ${small.fontSize}")
        assertTrue(small.lineHeight >= 20.sp, "line height is ${small.lineHeight}")
    }

    @Test
    fun otherStylesAreKept() {
        val base = Typography()
        assertEquals(base.bodyLarge, OzenTypography.of(base).bodyLarge)
    }
}
