package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteSizeTest {
    @Test
    fun `kilobytes, megabytes and gigabytes, decimal, as the model list writes them`() {
        assertEquals("0 KB", ByteSize.text(0))
        assertEquals("1 KB", ByteSize.text(400))
        assertEquals("640 KB", ByteSize.text(640_000))
        assertEquals("999 KB", ByteSize.text(999_000))
        assertEquals("1 MB", ByteSize.text(999_001))
        assertEquals("1 MB", ByteSize.text(1_000_000))
        assertEquals("819 MB", ByteSize.text(819_200_000))
        assertEquals("999 MB", ByteSize.text(999_400_000))
        assertEquals("1.0 GB", ByteSize.text(999_600_000))
        assertEquals("1.6 GB", ByteSize.text(1_619_000_000))
    }

    @Test
    fun `a size never wraps between its number and its unit, at any text size`() {
        val sizes = listOf(0L, 640_000L, 819_200_000L, 1_619_000_000L).map { ByteSize.text(it) }
        for (size in sizes) {
            assertFalse(size.contains(" "), size)
            assertTrue(size.contains(" "), size)
        }
    }
}
