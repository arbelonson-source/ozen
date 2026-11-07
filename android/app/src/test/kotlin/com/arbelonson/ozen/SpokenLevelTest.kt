package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenLevelTest {
    @Test
    fun `the first level is said at once`() {
        assertEquals(0.3f, SpokenLevel().heard(0.3f, nowMillis = 5_000))
    }

    @Test
    fun `within a second the level said stays, then the loudest of that second is said`() {
        val spoken = SpokenLevel()
        assertEquals(0.2f, spoken.heard(0.2f, nowMillis = 0))
        assertEquals(0.2f, spoken.heard(0.9f, nowMillis = 50))
        assertEquals(0.2f, spoken.heard(0.1f, nowMillis = 500))
        assertEquals(0.2f, spoken.heard(0.4f, nowMillis = 999))
        assertEquals(0.9f, spoken.heard(0.3f, nowMillis = 1_000))
        assertEquals(0.9f, spoken.heard(0.5f, nowMillis = 1_400))
        assertEquals(0.5f, spoken.heard(0.2f, nowMillis = 2_000))
    }

    @Test
    fun `a quiet second after a loud one is said as quiet`() {
        val spoken = SpokenLevel()
        spoken.heard(0.8f, nowMillis = 0)
        spoken.heard(0.05f, nowMillis = 300)
        assertEquals(0.05f, spoken.heard(0.02f, nowMillis = 1_000))
    }
}
