package com.arbelonson.ozen.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ServedModel(private val data: ByteArray) : ReleaseFileFetching {
    val fetchedFrom = mutableListOf<Long>()
    var cutOffAfter: Int? = null

    override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
        fetchedFrom.add(from)
        val start = from.toInt()
        val end = cutOffAfter?.let { minOf(start + it, data.size) } ?: data.size
        FileOutputStream(appendingTo, true).use { it.write(data, start, end - start) }
        received((end - start).toLong())
        if (end < data.size) throw IOException("connection lost")
    }

    override fun sha256(file: File): String = HTTPFileFetcher().sha256(file)
}

private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

class PhoneModelDownloaderTest {
    private val folder = Files.createTempDirectory("ozen-phone-model").toFile()
    private val file = File(folder, "ggml-model.bin")
    private val data = ByteArray(10_000) { (it * 13 % 251).toByte() }
    private val source = PhoneModelSource(URI("https://example.invalid/ggml-model.bin"), data.size.toLong(), sha256(data))

    @AfterTest
    fun clean() {
        folder.deleteRecursively()
    }

    @Test
    fun `the model takes its own name only once it is whole and as published, and nothing is left beside it`() {
        val fractions = mutableListOf<Double>()
        PhoneModelDownloader(file, source, ServedModel(data)).download { fractions.add(it) }
        assertContentEquals(data, file.readBytes())
        assertEquals(listOf("ggml-model.bin"), folder.list()!!.toList())
        assertEquals(1.0, fractions.last())
        assertEquals(fractions.sorted(), fractions)
    }

    @Test
    fun `a download cut off part way carries on from the part already on the phone`() {
        val served = ServedModel(data).apply { cutOffAfter = 4_000 }
        val downloader = PhoneModelDownloader(file, source, served)
        assertFailsWith<IOException> { downloader.download {} }
        assertFalse(file.exists())
        assertEquals(4_000L, downloader.part.length())
        served.cutOffAfter = null
        val fractions = mutableListOf<Double>()
        downloader.download { fractions.add(it) }
        assertEquals(listOf(0L, 4_000L), served.fetchedFrom)
        assertContentEquals(data, file.readBytes())
        assertEquals(0.4, fractions.first())
    }

    @Test
    fun `a model that arrives unlike the published one is thrown away, part and all, so the next try starts clean`() {
        val wrong = source.copy(sha256 = sha256(byteArrayOf(1, 2, 3)))
        assertFailsWith<PhoneModelDownloader.ChecksumMismatch> { PhoneModelDownloader(file, wrong, ServedModel(data)).download {} }
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test
    fun `a part longer than the model is started over`() {
        val served = ServedModel(data)
        val downloader = PhoneModelDownloader(file, source, served)
        downloader.part.writeBytes(data + data)
        downloader.download {}
        assertEquals(listOf(0L), served.fetchedFrom)
        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun `what is left to fetch is in megabytes rounded up, and nothing once the model is on the phone`() {
        val big = source.copy(bytes = 2_500_001)
        val downloader = PhoneModelDownloader(file, big, ServedModel(data))
        assertEquals(3, downloader.pendingMegabytes())
        downloader.part.writeBytes(ByteArray(1_500_001))
        assertEquals(1, downloader.pendingMegabytes())
        file.writeBytes(byteArrayOf(0))
        assertNull(downloader.pendingMegabytes())
    }

    @Test
    fun `two downloads of one model at once share it rather than writing into each other, and the one waiting hears how far it has got`() {
        val served = SlowModel(data)
        val failures = CopyOnWriteArrayList<Throwable>()
        val heard = CopyOnWriteArrayList<Double>()
        val underway = CountDownLatch(1)
        val first = thread {
            runCatching { PhoneModelDownloader(file, source, served).download { if (it > 0.1) underway.countDown() } }.onFailure { failures += it }
        }
        assertTrue(underway.await(10, TimeUnit.SECONDS))
        val second = thread {
            runCatching { PhoneModelDownloader(file, source, served).download { heard += it } }.onFailure { failures += it }
        }
        first.join(10_000)
        second.join(10_000)
        assertEquals(emptyList(), failures.toList())
        assertContentEquals(data, file.readBytes())
        assertEquals(data.size.toLong(), served.served.get(), "fetched more than once")
        assertTrue(heard.any { it > 0.1 && it < 1.0 }, "the waiting download heard $heard")
        assertEquals(1.0, heard.last())
    }
}

private class SlowModel(private val data: ByteArray) : ReleaseFileFetching {
    val served = AtomicLong()

    override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
        FileOutputStream(appendingTo, true).use { out ->
            var index = from.toInt()
            while (index < data.size) {
                val end = minOf(index + 100, data.size)
                out.write(data, index, end - index)
                out.flush()
                served.addAndGet((end - index).toLong())
                received((end - index).toLong())
                index = end
                Thread.sleep(5)
            }
        }
    }

    override fun sha256(file: File): String = HTTPFileFetcher().sha256(file)
}
