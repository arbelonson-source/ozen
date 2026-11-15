package com.arbelonson.ozen.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A computer that is off or asleep on the home network drops the first
 * packet of a connection instead of refusing it, so the connect itself
 * never completes. The check must still end within the app's own limit.
 * A listener whose queue is full drops new connections the same way, on
 * this machine and without a network.
 */
class HomeServerDroppedConnectTest {
    private fun saturated(): Pair<ServerSocket, List<Socket>> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val held = ArrayList<Socket>()
        while (held.size < 64) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(server.inetAddress, server.localPort), 300)
                held.add(socket)
            } catch (_: java.io.IOException) {
                socket.close()
                return server to held
            }
        }
        error("the listener never filled up")
    }

    @Test
    fun aComputerThatDropsTheConnectionIsGivenUpOnWithinTheHandshakeLimit() {
        val (server, held) = saturated()
        try {
            val engine = HomeServerEngine(
                address = "ws://127.0.0.1:${server.localPort}",
                token = { "code" },
                connector = WebSocketConnector(),
                handshakeSeconds = 1.0,
            )
            val started = System.nanoTime()
            val answer = runBlocking { withTimeout(8_000) { engine.checkAvailability("en") } }
            val seconds = (System.nanoTime() - started) / 1e9
            assertIs<EngineAvailability.Unavailable>(answer)
            assertTrue(seconds < 4.0, "the check took $seconds s with a 1 s limit")
        } finally {
            held.forEach { it.close() }
            server.close()
        }
    }
}
