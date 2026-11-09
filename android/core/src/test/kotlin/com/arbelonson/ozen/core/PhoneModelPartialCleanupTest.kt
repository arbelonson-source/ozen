package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneModelPartialCleanupTest {
    private val general = WhisperModelCatalog.phoneModelFile(UILanguage.English)
    private val hebrew = WhisperModelCatalog.phoneModelFile(UILanguage.Hebrew)
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun root(): File = Files.createTempDirectory("partials").toFile().also { it.deleteOnExit() }

    private fun File.put(name: String, ageDays: Double): File =
        File(this, name).also {
            it.writeBytes(ByteArray(10) { 1 })
            it.setLastModified(now - (ageDays * day).toLong())
        }

    @Test
    fun `a part file untouched for more than seven days goes, a newer one stays`() {
        val dir = root()
        val old = dir.put("$general.part", 8.0)
        val fresh = dir.put("$hebrew.part", 6.0)
        val removed = PhoneModelPartialCleanup.removeStale(dir, now)
        assertEquals(1, removed)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `finished models and other files are never touched however old`() {
        val dir = root()
        val model = dir.put(general, 40.0)
        val other = dir.put("notes.txt.part", 40.0)
        assertEquals(0, PhoneModelPartialCleanup.removeStale(dir, now))
        assertTrue(model.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `a part file of a model being downloaded right now stays even when old`() {
        val dir = root()
        val part = dir.put("$general.part", 30.0)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            PhoneModelDownloader.ifNotDownloading(File(dir, general)) {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(held.await(10, TimeUnit.SECONDS))
        val removed = PhoneModelPartialCleanup.removeStale(dir, now)
        release.countDown()
        holder.join()
        assertEquals(0, removed)
        assertTrue(part.exists())
        assertEquals(1, PhoneModelPartialCleanup.removeStale(dir, now))
        assertFalse(part.exists())
    }

    @Test
    fun `a folder that cannot be listed removes nothing`() {
        assertEquals(0, PhoneModelPartialCleanup.removeStale(File(root(), "missing"), now))
    }
}
