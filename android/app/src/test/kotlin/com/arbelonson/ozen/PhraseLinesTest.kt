package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class PhraseLinesTest {
    @Test
    fun `a phrase is cut to two lines at ordinary text sizes`() {
        assertEquals(2, PhraseLines.limit(1.0f))
        assertEquals(2, PhraseLines.limit(1.3f))
    }

    @Test
    fun `a phrase is shown whole at large text sizes`() {
        assertEquals(Int.MAX_VALUE, PhraseLines.limit(1.31f))
        assertEquals(Int.MAX_VALUE, PhraseLines.limit(2.0f))
    }
}
