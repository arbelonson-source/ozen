package com.arbelonson.ozen.core

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PinnedConnectionTest {
    private val folder = Files.createTempDirectory("ozen-pinned").toFile()
    private val keystore = File(folder, "server.p12")
    private val password = "changeit".toCharArray()
    private var server: SSLServerSocket? = null

    @AfterTest
    fun cleanUp() {
        server?.close()
        folder.deleteRecursively()
    }

    private fun serveACertificate(): String {
        val made = ProcessBuilder(
            "keytool", "-genkeypair", "-alias", "home", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Ozen home computer",
            "-validity", "3", "-keystore", keystore.path, "-storetype", "PKCS12", "-storepass", String(password),
        ).redirectErrorStream(true).start()
        made.inputStream.readBytes()
        assertTrue(made.waitFor(60, TimeUnit.SECONDS) && made.exitValue() == 0, "keytool failed")
        val store = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val socket = context.serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server = socket
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { (it as javax.net.ssl.SSLSocket).startHandshake() }
                } catch (_: IOException) {
                }
            }
        }
        return CertificatePin.of(store.getCertificate("home"))
    }

    private fun url() = URI("wss://127.0.0.1:${assertNotNull(server).localPort}")

    @Test
    fun `a certificate with the saved pin is accepted though nothing vouches for it and its name is not the address`() {
        val pin = serveACertificate()
        WebSocketConnector().connect(url(), pin).close()
    }

    @Test
    fun `another certificate is refused and the failure says the certificate did not match`() {
        serveACertificate()
        val refused = assertFailsWith<IOException> { WebSocketConnector().connect(url(), "EqUZTsqNgJ8Ol0fkhpLbMwToY-7X9n7_oEzJ2hZACBk") }
        assertEquals("certificate did not match", refused.message)
    }

    @Test
    fun `without a pin the same self-signed certificate is still refused by the system's trust`() {
        serveACertificate()
        assertFailsWith<IOException> { WebSocketConnector().connect(url()) }
    }

    @Test
    fun `a pin does not make a plain ws address acceptable`() {
        assertFailsWith<IllegalArgumentException> { WebSocketConnector().connect(URI("ws://192.168.1.20:8765"), "EqUZTsqNgJ8Ol0fkhpLbMwToY-7X9n7_oEzJ2hZACBk") }
    }
}
