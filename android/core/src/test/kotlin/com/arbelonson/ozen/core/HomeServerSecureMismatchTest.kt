package com.arbelonson.ozen.core

import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private class FailingSocket(private val failure: Throwable) : HomeServerSocket {
    override suspend fun send(text: String): Unit = throw failure
    override suspend fun send(data: ByteArray) {}
    override suspend fun receive(): String = throw failure
    override suspend fun ping() {}
    override suspend fun close() {}
}

private class FailingConnector(private val failure: Throwable, private val atOpen: Boolean) : HomeServerConnecting {
    override suspend fun open(url: URI): HomeServerSocket = if (atOpen) throw failure else FailingSocket(failure)
    override suspend fun openPinned(url: URI, certificatePin: String): HomeServerSocket = open(url)
}

class HomeServerSecureMismatchTest {
    private fun check(failure: Throwable, atOpen: Boolean): Pair<HomeServerCheck, EngineUnavailability?> = runBlocking {
        val engine = HomeServerEngine(address = "wss://192.168.1.20:8766", token = { "1234" }, connector = FailingConnector(failure, atOpen), handshakeSeconds = 5.0)
        val availability = engine.checkAvailability("he")
        HomeServerCheck.of(availability, 0.0) to availability.unavailability
    }

    @Test
    fun `a failed secure handshake, an untrusted certificate or a reply that is not a WebSocket answer means the computer answered`() {
        val failures = listOf(
            SSLHandshakeException("Remote host terminated the handshake"),
            SSLPeerUnverifiedException("Hostname not verified"),
            SSLException("Unable to parse TLS packet header"),
            SSLHandshakeException("Trust anchor for certification path not found."),
            IOException("wrapped", SSLHandshakeException("handshake_failure")),
            NotAWebSocketAnswer("not an HTTP answer: HELLO"),
            NotAWebSocketAnswer("the server's answer doesn't match this connection"),
            SocketRefused(200),
            SocketRefused(404),
        )
        for (failure in failures) for (atOpen in listOf(true, false)) {
            val (result, why) = check(failure, atOpen)
            assertEquals(HomeServerCheck.SecureMismatch, result, "$failure $atOpen")
            assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, why?.kind)
            assertTrue(HomeServer.answeredWrongly(why))
        }
    }

    @Test
    fun `no route, a refused connection, a timeout and a missing name stay unreachable`() {
        val failures = listOf(
            ConnectException("Connection refused"),
            SocketTimeoutException("connect timed out"),
            UnknownHostException("grandma-pc.local"),
            IOException("Network is unreachable"),
            java.io.EOFException("the connection closed while opening"),
            SSLException("timed out in the handshake", SocketTimeoutException("Read timed out")),
        )
        for (failure in failures) for (atOpen in listOf(true, false)) {
            val (result, why) = check(failure, atOpen)
            assertEquals(HomeServerCheck.Unreachable, result, "$failure $atOpen")
            assertFalse(HomeServer.answeredWrongly(why))
        }
    }

    @Test
    fun `a connection that drops in the middle of the secure handshake stays unreachable`() {
        val failures = listOf(
            SSLException("Connection reset", java.net.SocketException("Connection reset")),
            SSLException("Write error", java.net.SocketException("Broken pipe")),
            SSLHandshakeException("Software caused connection abort").also { it.initCause(java.net.SocketException("Software caused connection abort")) },
            SSLException("connection closed", java.io.EOFException("SSL peer shut down incorrectly")),
            IOException("wrapped", SSLException("Read error", java.net.SocketException("Connection reset"))),
            SSLException("Read error: ssl=0x7f1: I/O error during system call, Connection reset by peer"),
            SSLException("Write error: ssl=0x7f1: I/O error during system call, Broken pipe"),
            SSLHandshakeException("Handshake failed").also { it.initCause(java.io.EOFException("SSL peer shut down incorrectly")) },
        )
        for (failure in failures) for (atOpen in listOf(true, false)) {
            val (result, why) = check(failure, atOpen)
            assertEquals(HomeServerCheck.Unreachable, result, "$failure $atOpen")
            assertFalse(HomeServer.answeredWrongly(why))
        }
    }

    @Test
    fun `a real server that resets the connection during the handshake is unreachable, one that answers in plain text is a mismatch`() {
        fun outcome(serve: (java.net.Socket) -> Unit): HomeServerCheck {
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val thread = Thread { server.accept().use(serve) }.also { it.start() }
                val engine = HomeServerEngine(address = "wss://127.0.0.1:${server.localPort}", token = { "1234" }, connector = WebSocketConnector(), handshakeSeconds = 5.0)
                val availability = runBlocking { engine.checkAvailability("he") }
                thread.join()
                return HomeServerCheck.of(availability, 0.0)
            }
        }
        val reset = outcome { client ->
            client.getInputStream().read(ByteArray(512))
            client.setSoLinger(true, 0)
        }
        assertEquals(HomeServerCheck.Unreachable, reset)
        val plain = outcome { client ->
            client.getInputStream().read(ByteArray(512))
            client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
            client.getOutputStream().flush()
            Thread.sleep(300)
        }
        assertEquals(HomeServerCheck.SecureMismatch, plain)
    }

    @Test
    fun `the words say the computer answered and point at the port and the QR code`() {
        val why = EngineUnavailability(EngineUnavailability.Kind.HomeServerUnreachable, HomeServer.SECURE_MISMATCH_PREFIX + "x")
        Localization.withLanguage(UILanguage.English) {
            val text = HomeServer.secureMismatchText()
            assertTrue(text.startsWith("The computer answered, but the secure connection didn’t match"), text)
            assertTrue(text.contains("port number") && text.contains("QR code"), text)
            assertEquals(text, HomeServer.answeredWronglyText(why.detail))
            assertNotEquals(text, HomeServer.answeredWronglyText())
            assertEquals("$text · Tap to pause", HomeServer.addressCoverDetail(why.detail))
            assertEquals(
                "$text Open Ozen to continue.",
                StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.Failed(PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", why))).body,
            )
        }
    }

    @Test
    fun `a real server that answers the request to open with something else is named the same way`() {
        for (reply in listOf("HELLO there\r\n\r\n", "HELLO", "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")) {
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val thread = Thread {
                    server.accept().use { client ->
                        client.getInputStream().read(ByteArray(512))
                        client.getOutputStream().write(reply.toByteArray())
                        client.getOutputStream().flush()
                        Thread.sleep(300)
                    }
                }.also { it.start() }
                val engine = HomeServerEngine(address = "ws://127.0.0.1:${server.localPort}", token = { "1234" }, connector = WebSocketConnector(), handshakeSeconds = 5.0)
                val availability = runBlocking { engine.checkAvailability("he") }
                assertEquals(HomeServerCheck.SecureMismatch, HomeServerCheck.of(availability, 0.0), reply)
                assertNotNull(availability.unavailability)
                thread.join()
            }
        }
    }
}
