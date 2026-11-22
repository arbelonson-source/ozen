package com.arbelonson.ozen.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkedListTest {
    @Test
    fun `every version keeps the elements it had while later ones add and replace`() {
        val random = Random(7)
        var list = ChunkedList.empty<Int>()
        val versions = ArrayList<Pair<ChunkedList<Int>, List<Int>>>()
        val expected = ArrayList<Int>()
        repeat(3_000) { step ->
            if (expected.isEmpty() || random.nextInt(3) == 0) {
                list = list.adding(step)
                expected.add(step)
            } else {
                val at = if (random.nextBoolean()) expected.size - 1 else random.nextInt(expected.size)
                list = list.replacing(at, -step)
                expected[at] = -step
            }
            versions.add(list to expected.toList())
        }
        for ((version, had) in versions) {
            assertEquals(had, version)
            assertEquals(had.size, version.size)
            assertEquals(had.hashCode(), version.hashCode())
        }
    }

    @Test
    fun `a list made from others reads the same across chunk edges`() {
        for (count in listOf(0, 1, ChunkedList.CHUNK - 1, ChunkedList.CHUNK, ChunkedList.CHUNK + 1, 5 * ChunkedList.CHUNK)) {
            val source = (0 until count).map { "line $it" }
            val made = ChunkedList.of(source)
            assertEquals(source, made)
            assertEquals(source.lastOrNull(), made.lastOrNull())
            assertEquals(source.drop(3), made.drop(3))
            assertEquals(source + "next", made.adding("next"))
        }
    }

    @Test
    fun `reading outside the list is refused as for any list`() {
        val list = ChunkedList.of(listOf(1, 2, 3))
        assertFailsWith<IndexOutOfBoundsException> { list[3] }
        assertFailsWith<IndexOutOfBoundsException> { list[-1] }
        assertFailsWith<IndexOutOfBoundsException> { list.replacing(3, 9) }
        assertFailsWith<IndexOutOfBoundsException> { ChunkedList.empty<Int>()[0] }
        assertTrue(ChunkedList.empty<Int>().isEmpty())
    }
}
