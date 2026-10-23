package com.arbelonson.ozen

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharedFileTest {
    private fun withCache(body: (File) -> Unit) {
        val cache = Files.createTempDirectory("ozen-shared").toFile()
        try {
            body(cache)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun aSecondExportLeavesTheFirstOneForWhoeverIsStillReadingIt() = withCache { cache ->
        val first = SharedFile.create(cache, "Lecture.srt").also { it.writeText("first") }
        val second = SharedFile.create(cache, "Lecture.srt").also { it.writeText("second") }
        assertNotEquals(first, second)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertEquals(CaptionSession.SHARED_FOLDER, first.parentFile!!.parentFile!!.name)
    }

    @Test
    fun exportsOlderThanADayAreCleanedUpWhenTheNextOneIsMade() = withCache { cache ->
        val old = SharedFile.create(cache, "Old.srt").also { it.writeText("old") }
        val now = System.currentTimeMillis()
        old.parentFile!!.setLastModified(now - 25L * 60 * 60 * 1_000)
        val fresh = SharedFile.create(cache, "New.srt", now)
        assertFalse(old.exists())
        assertTrue(fresh.parentFile!!.exists())
    }
}
