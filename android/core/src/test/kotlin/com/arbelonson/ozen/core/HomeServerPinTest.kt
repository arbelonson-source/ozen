package com.arbelonson.ozen.core

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MismatchOnFirstSend : HomeServerSocket {
    override suspend fun send(text: String): Unit = throw java.io.IOException(PinnedTrustManager.MISMATCH)
    override suspend fun send(data: ByteArray) {}
    override suspend fun receive(): String = throw java.io.IOException(PinnedTrustManager.MISMATCH)
    override suspend fun ping() {}
    override suspend fun close() {}
}

private class LazyConnector(private val socket: HomeServerSocket) : HomeServerConnecting {
    override suspend fun open(url: URI): HomeServerSocket = socket
    override suspend fun openPinned(url: URI, certificatePin: String): HomeServerSocket = socket
}

class HomeServerPinTest {
    private val pin = "EqUZTsqNgJ8Ol0fkhpLbMwToY-7X9n7_oEzJ2hZACBk"
    private val link = "ozen://pair?address=wss%3A%2F%2F192.168.1.20%3A8766&code=example-code-123&pin=$pin"

    @Test
    fun `a pairing link with a pin gives the address, the code and the pin`() {
        val pairing = assertNotNull(HomeServerPairing.from(URI(link)))
        assertEquals("wss://192.168.1.20:8766", pairing.address)
        assertEquals("example-code-123", pairing.code)
        assertEquals(pin, pairing.pin)
    }

    @Test
    fun `a pairing link without a pin has none`() {
        val pairing = assertNotNull(HomeServerPairing.from(URI("ozen://pair?address=wss://desktop.tail.ts.net&code=example-code-123")))
        assertNull(pairing.pin)
    }

    @Test
    fun `a pin that is not a certificate fingerprint makes the link damaged instead of being dropped`() {
        for (bad in listOf("", "short", "$pin=", pin.replace('-', '+'), pin + "A", pin.dropLast(1) + "l")) {
            assertNull(HomeServerPairing.from(URI("ozen://pair?address=wss%3A%2F%2F192.168.1.20%3A8766&code=example-code-123&pin=$bad")), bad)
        }
    }

    @Test
    fun `a pin with an address that is not encrypted makes the link damaged`() {
        for (address in listOf("192.168.1.20", "ws%3A%2F%2F192.168.1.20%3A8765", "grandma-pc")) {
            assertNull(HomeServerPairing.from(URI("ozen://pair?address=$address&code=example-code-123&pin=$pin")), address)
        }
        assertNull(HomeServerPairing("ws://192.168.1.20:8765", "example-code-123", pin))
    }

    @Test
    fun `the link made from a pairing carries the pin and reads back the same, and differs from one without`() {
        val pairing = assertNotNull(HomeServerPairing("wss://192.168.1.20:8766", "example-code-123", pin))
        assertEquals(pairing, HomeServerPairing.from(pairing.url))
        assertTrue(pairing.url.toString().endsWith("&pin=$pin"))
        assertFalse(pairing == HomeServerPairing("wss://192.168.1.20:8766", "example-code-123"))
    }

    @Test
    fun `only a home-network address without a pin is called unencrypted`() {
        assertTrue(HomeServer.homeLinkIsPlain("192.168.1.20", null))
        assertTrue(HomeServer.homeLinkIsPlain("ws://grandma-pc:8765", null))
        assertTrue(HomeServer.homeLinkIsPlain("ws://100.64.0.7:8765", null))
        assertFalse(HomeServer.homeLinkIsPlain("wss://192.168.1.20:8766", pin))
        assertFalse(HomeServer.homeLinkIsPlain("192.168.1.20", pin))
        assertFalse(HomeServer.homeLinkIsPlain("wss://desktop.tail.ts.net", null))
        assertFalse(HomeServer.homeLinkIsPlain("", null))
        assertFalse(HomeServer.homeLinkIsPlain("ws://203.0.113.9:8765", null))
        assertFalse(HomeServer.homeLinkIsPlain("not an address", null))
    }
}

private class PinRecordingConnector(private val socket: ScriptedSocket, private val failure: Exception? = null) : HomeServerConnecting {
    val opened = mutableListOf<String>()

    override suspend fun open(url: URI): HomeServerSocket {
        opened += "plain $url"
        return socket
    }

    override suspend fun openPinned(url: URI, certificatePin: String): HomeServerSocket {
        opened += "pinned $url $certificatePin"
        failure?.let { throw it }
        return socket
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeServerEnginePinTest {
    private val pin = "EqUZTsqNgJ8Ol0fkhpLbMwToY-7X9n7_oEzJ2hZACBk"

    private fun kotlinx.coroutines.test.TestScope.engine(connector: HomeServerConnecting, address: String, certificatePin: String?) =
        HomeServerEngine(
            address = address,
            token = { "1234" },
            connector = connector,
            handshakeSeconds = 1.0,
            certificatePin = certificatePin,
            timeSource = testScheduler.timeSource,
        )

    @Test
    fun `a saved pin makes the connection a pinned one to the saved address`() = kotlinx.coroutines.test.runTest {
        val connector = PinRecordingConnector(ScriptedSocket(SCRIPTED_READY, emptyList(), testScheduler.timeSource))
        assertEquals(EngineAvailability.Available, engine(connector, "wss://192.168.1.20:8766", pin).checkAvailability("he"))
        assertEquals(listOf("pinned wss://192.168.1.20:8766 $pin"), connector.opened)
    }

    @Test
    fun `without a pin the connection is opened as before`() = kotlinx.coroutines.test.runTest {
        val connector = PinRecordingConnector(ScriptedSocket(SCRIPTED_READY, emptyList(), testScheduler.timeSource))
        assertEquals(EngineAvailability.Available, engine(connector, "192.168.1.20", null).checkAvailability("he"))
        assertEquals(listOf("plain ws://192.168.1.20:8765"), connector.opened)
    }

    @Test
    fun `a certificate that did not match needs the QR code scanned again and the reason is kept for the journal`() = kotlinx.coroutines.test.runTest {
        val connector = PinRecordingConnector(
            ScriptedSocket(SCRIPTED_READY, emptyList(), testScheduler.timeSource),
            java.io.IOException(PinnedTrustManager.MISMATCH),
        )
        val why = assertNotNull(engine(connector, "wss://192.168.1.20:8766", pin).checkAvailability("he").unavailability)
        assertEquals(EngineUnavailability.Kind.HomeServerRejected, why.kind)
        assertTrue(why.detail.contains("certificate did not match"), why.detail)
    }

    @Test
    fun `a mismatch buried in the cause chain still needs the QR code scanned again`() = kotlinx.coroutines.test.runTest {
        val connector = PinRecordingConnector(
            ScriptedSocket(SCRIPTED_READY, emptyList(), testScheduler.timeSource),
            java.io.IOException("wrapped", javax.net.ssl.SSLException("tls", java.io.IOException(PinnedTrustManager.MISMATCH))),
        )
        val why = assertNotNull(engine(connector, "wss://192.168.1.20:8766", pin).checkAvailability("he").unavailability)
        assertEquals(EngineUnavailability.Kind.HomeServerRejected, why.kind)
    }

    @Test
    fun `any other connect failure is still an unreachable computer`() = kotlinx.coroutines.test.runTest {
        val connector = PinRecordingConnector(
            ScriptedSocket(SCRIPTED_READY, emptyList(), testScheduler.timeSource),
            java.io.IOException("connection refused"),
        )
        val why = assertNotNull(engine(connector, "wss://192.168.1.20:8766", pin).checkAvailability("he").unavailability)
        assertEquals(EngineUnavailability.Kind.HomeServerUnreachable, why.kind)
    }

    @Test
    fun `a mismatch that only shows on the first send, as the real connector does it, needs the QR code scanned again`() = kotlinx.coroutines.test.runTest {
        val why = assertNotNull(engine(LazyConnector(MismatchOnFirstSend()), "wss://192.168.1.20:8766", pin).checkAvailability("he").unavailability)
        assertEquals(EngineUnavailability.Kind.HomeServerRejected, why.kind)
    }
}
