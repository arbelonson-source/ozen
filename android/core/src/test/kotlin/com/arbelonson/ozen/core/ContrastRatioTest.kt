package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val white = Triple(1.0, 1.0, 1.0)
private val black = Triple(0.0, 0.0, 0.0)

class ContrastRatioTest {
    @Test
    fun `white on black, and black on white, are both the maximum, 21 to 1`() {
        assertTrue(abs(ContrastRatio.between(white, black) - 21) < 0.01)
        assertTrue(abs(ContrastRatio.between(black, white) - 21) < 0.01)
    }

    @Test
    fun `the same colour twice has no contrast at all`() {
        assertEquals(1.0, ContrastRatio.between(white, white))
        assertEquals(1.0, ContrastRatio.between(Triple(0.4, 0.2, 0.6), Triple(0.4, 0.2, 0.6)))
    }

    @Test
    fun `a translucent colour's contrast is worked out against what it's drawn over`() {
        val blended = ContrastRatio.ratio(foreground = white, alpha = 0.5, overBackground = black)
        val solid = ContrastRatio.between(Triple(0.5, 0.5, 0.5), black)
        assertTrue(abs(blended - solid) < 0.0001)
    }

    @Test
    fun `over a coloured background, the background shows through a translucent colour`() {
        val card = Triple(0.8, 0.4, 0.6)
        val blended = ContrastRatio.ratio(foreground = black, alpha = 0.25, overBackground = card)
        val solid = ContrastRatio.between(Triple(0.6, 0.3, 0.45), card)
        assertTrue(abs(blended - solid) < 0.0001)
    }

    @Test
    fun `reference greys, 767676 on white is 4_54 to 1, and a near-black grey 19_47 to 1`() {
        val gray = 118.0 / 255
        assertTrue(abs(ContrastRatio.between(Triple(gray, gray, gray), white) - 4.54) < 0.01)
        assertTrue(abs(ContrastRatio.between(Triple(0.05, 0.05, 0.05), white) - 19.47) < 0.01)
    }

    @Test
    fun `a more opaque foreground contrasts more with its background`() {
        val dim = ContrastRatio.ratio(foreground = white, alpha = 0.5, overBackground = black)
        val bright = ContrastRatio.ratio(foreground = white, alpha = 0.8, overBackground = black)
        assertTrue(bright > dim)
    }
}
