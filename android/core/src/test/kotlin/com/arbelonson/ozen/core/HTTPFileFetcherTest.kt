package com.arbelonson.ozen.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HTTPFileFetcherTest {
    private val model = ByteArray(300_000) { (it * 7 % 251).toByte() }
    private val ranges = mutableListOf<String?>()
    private var honoursRange = true
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/model.bin") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            synchronized(ranges) { ranges.add(range) }
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()?.takeIf { honoursRange } ?: 0
            if (from > 0) exchange.responseHeaders.add("Content-Range", "bytes $from-${model.size - 1}/${model.size}")
            exchange.sendResponseHeaders(if (from > 0) 206 else 200, (model.size - from).toLong())
            exchange.responseBody.use { it.write(model, from, model.size - from) }
        }
        createContext("/moved") { exchange ->
            synchronized(ranges) { ranges.add(exchange.requestHeaders.getFirst("Range")) }
            exchange.responseHeaders.add("Location", "/model.bin")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        createContext("/elsewhere") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-${model.size - 1}/${model.size}")
            exchange.sendResponseHeaders(206, model.size.toLong())
            exchange.responseBody.use { it.write(model) }
        }
        createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, 3)
            exchange.responseBody.use { it.write("no!".toByteArray()) }
        }
        start()
    }
    private val folder = Files.createTempDirectory("ozen-fetch").toFile()
    private val file = File(folder, "model.bin.part")

    private fun address(path: String) = URI("http://127.0.0.1:${server.address.port}$path")

    @AfterTest
    fun stop() {
        server.stop(0)
        folder.deleteRecursively()
    }

    @Test
    fun `a whole file arrives as served, every byte of it reported`() {
        var reported = 0L
        HTTPFileFetcher().fetch(address("/model.bin"), 0, file) { reported += it }
        assertContentEquals(model, file.readBytes())
        assertEquals(model.size.toLong(), reported)
        assertEquals(listOf<String?>(null), ranges)
    }

    @Test
    fun `a cut-off file carries on from where it stopped, asking only for the rest`() {
        file.writeBytes(model.copyOf(120_000))
        var reported = 0L
        HTTPFileFetcher().fetch(address("/model.bin"), 120_000, file) { reported += it }
        assertContentEquals(model, file.readBytes())
        assertEquals(180_000L, reported)
        assertEquals(listOf<String?>("bytes=120000-"), ranges)
    }

    @Test
    fun `a server that sends the whole file again starts the file over and says so once`() {
        honoursRange = false
        file.writeBytes(model.copyOf(120_000))
        val reports = mutableListOf<Long>()
        HTTPFileFetcher().fetch(address("/model.bin"), 120_000, file) { reports.add(it) }
        assertContentEquals(model, file.readBytes())
        assertEquals(-120_000L, reports.first())
        assertEquals(model.size.toLong(), reports.drop(1).sum())
    }

    @Test
    fun `a redirect is followed with the same range, as Hugging Face hands its files out from another host`() {
        file.writeBytes(model.copyOf(1_000))
        HTTPFileFetcher().fetch(address("/moved"), 1_000, file) {}
        assertContentEquals(model, file.readBytes())
        assertEquals(listOf<String?>("bytes=1000-", "bytes=1000-"), ranges)
    }

    @Test
    fun `a part sent from somewhere other than where the file stopped is refused, not stacked onto it`() {
        file.writeBytes(model.copyOf(1_000))
        assertFailsWith<IOException> { HTTPFileFetcher().fetch(address("/elsewhere"), 1_000, file) {} }
        assertContentEquals(model.copyOf(1_000), file.readBytes())
    }

    @Test
    fun `a refused request fails and leaves the part as it was, never the error page written into the model`() {
        file.writeBytes(model.copyOf(1_000))
        assertFailsWith<IOException> { HTTPFileFetcher().fetch(address("/missing"), 1_000, file) {} }
        assertContentEquals(model.copyOf(1_000), file.readBytes())
    }

    @Test
    fun `a redirect may move to another host or path but never from https down to plain http`() {
        val start = URI("https://huggingface.co/ggerganov/whisper.cpp/resolve/abc/model.bin")
        assertEquals(URI("https://cdn.example/files/1?x=y"), HTTPFileFetcher.redirectTarget(start, "https://cdn.example/files/1?x=y"))
        assertEquals(URI("https://huggingface.co/other/model.bin"), HTTPFileFetcher.redirectTarget(start, "/other/model.bin"))
        assertFailsWith<IOException> { HTTPFileFetcher.redirectTarget(start, "http://cdn.example/files/1") }
    }

    @Test
    fun `the checksum is SHA-256 in lowercase hex`() {
        file.writeBytes("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HTTPFileFetcher().sha256(file))
    }
}
