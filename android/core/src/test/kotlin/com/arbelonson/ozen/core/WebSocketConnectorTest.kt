package com.arbelonson.ozen.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private class WireFrame(val isFinal: Boolean, val opcode: Int, val payload: ByteArray, val masked: Boolean) {
    val text: String get() = payload.toString(Charsets.UTF_8)
}

/** A WebSocket server on 127.0.0.1 (Android's loopback address is ::1) that runs one scripted conversation. */
private class ScriptedWebSocketServer(
    private val status: Int = 101,
    private val forgeAccept: Boolean = false,
    private val answerAfterMillis: Long = 0,
    private val script: ScriptedWebSocketServer.Peer.() -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val requestLines = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<Throwable>()
    private val finished = CountDownLatch(1)

    inner class Peer(private val socket: Socket, private val input: InputStream, private val output: OutputStream) {
        fun readFrame(): WireFrame {
            val first = input.read()
            val second = input.read()
            if (first < 0 || second < 0) throw IOException("client closed")
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
            if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or input.read().toLong() }
            }
            val masked = second and 0x80 != 0
            val mask = if (masked) input.readNBytes(4) else ByteArray(4)
            val payload = input.readNBytes(length.toInt())
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return WireFrame(first and 0x80 != 0, first and 0x0F, payload, masked)
        }

        fun send(opcode: Int, payload: ByteArray, isFinal: Boolean = true, masked: Boolean = false) {
            val out = ByteArrayOutputStream()
            out.write((if (isFinal) 0x80 else 0) or opcode)
            val maskBit = if (masked) 0x80 else 0
            when {
                payload.size < 126 -> out.write(maskBit or payload.size)
                payload.size < 65_536 -> {
                    out.write(maskBit or 126)
                    out.write(payload.size shr 8)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(maskBit or 127)
                    for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            if (masked) out.write(ByteArray(4))
            out.write(payload)
            output.write(out.toByteArray())
            output.flush()
        }

        fun sendText(text: String, isFinal: Boolean = true) = send(1, text.toByteArray(Charsets.UTF_8), isFinal)

        fun sendClose(code: Int, reason: String) =
            send(8, byteArrayOf((code shr 8).toByte(), (code and 0xFF).toByte()) + reason.toByteArray(Charsets.UTF_8))

        fun hangUp() = socket.close()
    }

    init {
        thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    var key = ""
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        requestLines.add(line)
                        if (line.lowercase().startsWith("sec-websocket-key:")) key = line.substringAfter(':').trim()
                    }
                    if (answerAfterMillis > 0) Thread.sleep(answerAfterMillis)
                    if (status != 101) {
                        output.write("HTTP/1.1 $status Unauthorized\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        output.flush()
                        return@use
                    }
                    val accept = if (forgeAccept) WebSocketConnector.acceptKey("someone else") else WebSocketConnector.acceptKey(key)
                    output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                    output.flush()
                    Peer(socket, input, output).script()
                }
            } catch (error: Throwable) {
                failures.add(error)
            } finally {
                finished.countDown()
            }
        }
    }

    fun url(path: String = "/"): URI = URI("ws://127.0.0.1:$port$path")

    fun awaitScript() = assertTrue(finished.await(5, TimeUnit.SECONDS), "the server's script never finished")

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0 || byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
    }

    override fun close() = server.close()
}

class WebSocketConnectorTest {
    @Test
    fun `text goes both ways at every length the frame header has`() = runBlocking<Unit> {
        val heard = CopyOnWriteArrayList<WireFrame>()
        ScriptedWebSocketServer {
            heard.add(readFrame())
            heard.add(readFrame())
            sendText("hi")
            sendText("א".repeat(150))
            sendText("b".repeat(70_000))
        }.use { server ->
            val socket = WebSocketConnector().open(server.url())
            socket.send("hello")
            socket.send("c".repeat(70_000))
            withTimeout(5_000) {
                assertEquals("hi", socket.receive())
                assertEquals("א".repeat(150), socket.receive())
                assertEquals("b".repeat(70_000), socket.receive())
            }
            server.awaitScript()
            assertEquals(listOf("hello", "c".repeat(70_000)), heard.map { it.text })
            assertTrue(heard.all { it.opcode == 1 && it.masked }, "a client frame must be masked text")
            socket.close()
        }
    }

    @Test
    fun `audio goes out as binary frames, whole`() = runBlocking<Unit> {
        val heard = CopyOnWriteArrayList<WireFrame>()
        val audio = ByteArray(3_200) { (it * 7).toByte() }
        ScriptedWebSocketServer { heard.add(readFrame()) }.use { server ->
            val socket = WebSocketConnector().open(server.url())
            socket.send(audio)
            server.awaitScript()
            assertEquals(2, heard.single().opcode)
            assertContentEquals(audio, heard.single().payload)
            socket.close()
        }
    }

    @Test
    fun `a message sent in pieces comes back whole, and a ping between the pieces is answered`() = runBlocking<Unit> {
        val heard = CopyOnWriteArrayList<WireFrame>()
        ScriptedWebSocketServer {
            sendText("שלום ", isFinal = false)
            send(0, "עולם".toByteArray(Charsets.UTF_8), isFinal = false)
            send(9, byteArrayOf(1, 2, 3))
            heard.add(readFrame())
            send(0, "!".toByteArray(Charsets.UTF_8))
        }.use { server ->
            val socket = WebSocketConnector().open(server.url())
            assertEquals("שלום עולם!", withTimeout(5_000) { socket.receive() })
            server.awaitScript()
            assertEquals(10, heard.single().opcode)
            assertContentEquals(byteArrayOf(1, 2, 3), heard.single().payload)
            socket.close()
        }
    }

    @Test
    fun `binary the server sends is skipped`() = runBlocking<Unit> {
        ScriptedWebSocketServer {
            send(2, byteArrayOf(9, 9, 9))
            sendText("after")
        }.use { server ->
            val socket = WebSocketConnector().open(server.url())
            assertEquals("after", withTimeout(5_000) { socket.receive() })
            socket.close()
        }
    }

    @Test
    fun `a ping returns once the server answers it`() = runBlocking<Unit> {
        ScriptedWebSocketServer {
            val ping = readFrame()
            assertEquals(9, ping.opcode)
            send(10, ping.payload)
        }.use { server ->
            val socket = WebSocketConnector().open(server.url())
            withTimeout(5_000) { socket.ping() }
            server.awaitScript()
            assertTrue(server.failures.isEmpty(), "${server.failures}")
            socket.close()
        }
    }

    @Test
    fun `a ping the server never answers keeps waiting, and closing ends it`() = runBlocking<Unit> {
        val gotPing = CountDownLatch(1)
        val release = CountDownLatch(1)
        ScriptedWebSocketServer {
            readFrame()
            gotPing.countDown()
            release.await(5, TimeUnit.SECONDS)
        }.use { server ->
            val socket = WebSocketConnector().open(server.url())
            val ping = async(Dispatchers.IO) { runCatching { socket.ping() } }
            assertTrue(gotPing.await(5, TimeUnit.SECONDS))
            delay(300)
            assertFalse(ping.isCompleted, "a ping with no answer returned")
            socket.close()
            assertTrue(withTimeout(5_000) { ping.await() }.isFailure)
            release.countDown()
        }
    }

    @Test
    fun `the server's close code and reason come through, and the close is answered`() = runBlocking<Unit> {
        val heard = CopyOnWriteArrayList<WireFrame>()
        ScriptedWebSocketServer {
            sendClose(4001, "unauthorized")
            heard.add(readFrame())
        }.use { server ->
            val socket = WebSocketConnector().open(server.url())
            val closed = assertFailsWith<SocketClosed> { withTimeout(5_000) { socket.receive() } }
            assertEquals(SocketClosed(4001, "unauthorized"), closed)
            server.awaitScript()
            assertEquals(8, heard.single().opcode)
            assertContentEquals(byteArrayOf(0x0F, 0xA1.toByte()), heard.single().payload)
        }
    }

    @Test
    fun `an opening the server refuses is SocketRefused with its status`() = runBlocking<Unit> {
        ScriptedWebSocketServer(status = 401) {}.use { server ->
            val socket = WebSocketConnector().open(server.url())
            assertEquals(SocketRefused(401), assertFailsWith<SocketRefused> { withTimeout(5_000) { socket.receive() } })
            assertFailsWith<SocketRefused> { socket.send("hello") }
        }
    }

    @Test
    fun `an answer made for another connection is not accepted`() = runBlocking<Unit> {
        ScriptedWebSocketServer(forgeAccept = true) {}.use { server ->
            val socket = WebSocketConnector().open(server.url())
            val error = assertFailsWith<IOException> { withTimeout(5_000) { socket.receive() } }
            assertTrue(error.message!!.contains("doesn't match"), "$error")
        }
    }

    @Test
    fun `opening returns before the server answers, and the connection is there once it does`() = runBlocking<Unit> {
        ScriptedWebSocketServer(answerAfterMillis = 800) { sendText("ready") }.use { server ->
            val started = System.nanoTime()
            val socket = WebSocketConnector().open(server.url())
            val openMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(openMillis < 400, "open took $openMillis ms")
            assertEquals("ready", withTimeout(5_000) { socket.receive() })
            socket.close()
        }
    }

    @Test
    fun `the path, query and headers go with the opening`() = runBlocking<Unit> {
        ScriptedWebSocketServer { sendText("ok") }.use { server ->
            val socket = WebSocketConnector().open(server.url("/v1/listen?model=nova-3&language=he"), mapOf("Authorization" to "Token abc"))
            assertEquals("ok", withTimeout(5_000) { socket.receive() })
            assertEquals("GET /v1/listen?model=nova-3&language=he HTTP/1.1", server.requestLines.first())
            assertTrue(server.requestLines.contains("Authorization: Token abc"), "${server.requestLines}")
            assertTrue(server.requestLines.contains("Host: 127.0.0.1:${server.port}"), "${server.requestLines}")
            socket.close()
        }
    }

    @Test
    fun `a header holding a line break is refused before anything is sent`() = runBlocking<Unit> {
        val error = assertFailsWith<IllegalArgumentException> {
            WebSocketConnector().open(URI("ws://127.0.0.1:9/"), mapOf("Authorization" to "Token abc\r\nX-Evil: 1"))
        }
        assertTrue(error.message!!.contains("line break"))
    }

    @Test
    fun `a connection cut without a close is an error, not a close code`() = runBlocking<Unit> {
        ScriptedWebSocketServer { hangUp() }.use { server ->
            val socket = WebSocketConnector().open(server.url())
            val error = assertFailsWith<Exception> { withTimeout(5_000) { socket.receive() } }
            assertFalse(error is SocketClosed, "$error")
            assertIs<IOException>(error)
        }
    }

    @Test
    fun `a frame the server masked is refused`() = runBlocking<Unit> {
        ScriptedWebSocketServer { send(1, "hi".toByteArray(), masked = true) }.use { server ->
            val socket = WebSocketConnector().open(server.url())
            val error = assertFailsWith<IOException> { withTimeout(5_000) { socket.receive() } }
            assertTrue(error.message!!.contains("masked"), "$error")
        }
    }
}
