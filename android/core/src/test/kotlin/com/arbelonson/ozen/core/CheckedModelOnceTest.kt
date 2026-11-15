package com.arbelonson.ozen.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckedModelOnceTest {
    private val folder = Files.createTempDirectory("ozen-checked-once").toFile()
    private val file = File(folder, "ggml-model.bin")
    private val data = ByteArray(10_000) { (it * 13 % 251).toByte() }
    private val published = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val source = PhoneModelSource(URI("https://example.invalid/ggml-model.bin"), data.size.toLong(), published)
    private var hashes = 0
    private val fetcher = object : ReleaseFileFetching {
        override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) = Unit

        override fun sha256(file: File): String {
            hashes += 1
            return HTTPFileFetcher().sha256(file)
        }
    }

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun `a file found fine is not read again while it is unchanged`() {
        file.writeBytes(data)
        val downloader = PhoneModelDownloader(file, source, fetcher)
        assertFalse(downloader.removeIfDamaged())
        assertEquals(1, hashes)
        assertFalse(downloader.removeIfDamaged())
        assertFalse(PhoneModelDownloader(file, source, fetcher).removeIfDamaged())
        assertEquals(1, hashes)
    }

    @Test
    fun `a file whose modification time changed is read again`() {
        file.writeBytes(data)
        val downloader = PhoneModelDownloader(file, source, fetcher)
        assertFalse(downloader.removeIfDamaged())
        file.setLastModified(file.lastModified() + 60_000)
        assertFalse(downloader.removeIfDamaged())
        assertEquals(2, hashes)
    }

    @Test
    fun `a file changed after it was found fine is found damaged and removed`() {
        file.writeBytes(data)
        val downloader = PhoneModelDownloader(file, source, fetcher)
        assertFalse(downloader.removeIfDamaged())
        file.writeBytes(data.copyOf().also { it[7] = (it[7] + 1).toByte() })
        file.setLastModified(file.lastModified() + 60_000)
        assertTrue(downloader.removeIfDamaged())
        assertFalse(file.exists())
        assertEquals(2, hashes)
    }

    @Test
    fun `a file of another size is read again`() {
        file.writeBytes(data)
        assertFalse(PhoneModelDownloader(file, source, fetcher).removeIfDamaged())
        val other = source.copy(bytes = data.size - 1L)
        file.writeBytes(data.copyOf(data.size - 1))
        assertTrue(PhoneModelDownloader(file, other, fetcher).removeIfDamaged())
    }
}
