package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class MovingWordTest {
    private val words = listOf("Shira", "Dr. Levi", "Amoxicillin")

    @Test
    fun `a word moves one place up or down and no further than the ends`() {
        assertEquals(listOf("Dr. Levi", "Shira", "Amoxicillin"), movingWord("Dr. Levi", -1, words))
        assertEquals(listOf("Shira", "Amoxicillin", "Dr. Levi"), movingWord("Dr. Levi", 1, words))
        assertEquals(words, movingWord("Shira", -1, words))
        assertEquals(words, movingWord("Amoxicillin", 1, words))
        assertEquals(words, movingWord("Grandma", -1, words))
    }
}
