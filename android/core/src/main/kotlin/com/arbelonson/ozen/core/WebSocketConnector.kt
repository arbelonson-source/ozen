package com.arbelonson.ozen.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens WebSocket connections (RFC 6455) for the home computer and the
 * cloud services' live streams, the way the iPhone's URLSession socket
 * does: [open] returns at once and the connection is made behind it, so a
 * dead address fails the first send or receive inside the engine's own
 * timed wait rather than holding [open] for the system's connect timeout.
 *
 * Written here rather than taken from a library because the engines need a
 * ping whose answer can be awaited (that is how a computer that stopped
 * answering is noticed), which OkHttp doesn't offer. `wss` goes through the
 * platform's TLS with the host name checked against the certificate.
 */
class WebSocketConnector(
    private val plainSockets: SocketFactory = SocketFactory.getDefault(),
    private val tlsSockets: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val connectTimeoutMillis: Int = 15_000,
) : HomeServerConnecting, CloudSocketConnecting {
    override suspend fun open(url: URI): HomeServerSocket = open(url, emptyMap())

    override suspend fun open(url: URI, headers: Map<String, String>): HomeServerSocket {
        for ((name, value) in headers) {
            require(name.none { it == '\r' || it == '\n' || it == ':' } && value.none { it == '\r' || it == '\n' }) {
                "a header can't hold a line break"
            }
        }
        val socket = WebSocketConnection(url, headers, this)
        socket.start()
        return socket
    }

    internal fun connect(url: URI): Socket {
        val scheme = url.scheme?.lowercase()
        require(scheme == "ws" || scheme == "wss") { "not a WebSocket address: $url" }
        val host = requireNotNull(url.host) { "no host in $url" }.trim('[', ']')
        val secure = scheme == "wss"
        val port = if (url.port > 0) url.port else if (secure) 443 else 80
        val plain = plainSockets.createSocket()
        try {
            plain.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            plain.tcpNoDelay = true
            if (!secure) return plain
            val tls = tlsSockets.createSocket(plain, host, port, true) as SSLSocket
            val parameters = tls.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            if (!isAddressLiteral(host)) parameters.serverNames = listOf(SNIHostName(host))
            tls.sslParameters = parameters
            tls.startHandshake()
            return tls
        } catch (error: Throwable) {
            plain.close()
            throw error
        }
    }

    private fun isAddressLiteral(host: String): Boolean =
        host.contains(':') || host.split('.').let { parts -> parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) } }

    internal companion object {
        const val MAXIMUM_MESSAGE_BYTES = 1 shl 20
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun acceptKey(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))
    }
}

private class Streams(val socket: Socket, val input: InputStream, val output: OutputStream)

private class WebSocketConnection(
    private val url: URI,
    private val headers: Map<String, String>,
    private val connector: WebSocketConnector,
) : HomeServerSocket {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection = CompletableDeferred<Streams>()
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val random = SecureRandom()
    private val lock = Any()
    private val pongs = ArrayDeque<CompletableDeferred<Unit>>()
    private var failure: Throwable? = null

    fun start() {
        scope.launch {
            val streams = try {
                handshake()
            } catch (error: Throwable) {
                fail(error)
                return@launch
            }
            connection.complete(streams)
            try {
                readLoop(streams)
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    override suspend fun send(text: String) = write(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    override suspend fun send(data: ByteArray) = write(OPCODE_BINARY, data)

    override suspend fun receive(): String = incoming.receive()

    override suspend fun ping() {
        val answered = CompletableDeferred<Unit>()
        synchronized(lock) {
            failure?.let { throw it }
            pongs.addLast(answered)
        }
        write(OPCODE_PING, ByteArray(4).also(random::nextBytes))
        answered.await()
    }

    override suspend fun close() {
        if (connection.isCompleted && synchronized(lock) { failure } == null) {
            withTimeoutOrNull(1_000) {
                runCatching { write(OPCODE_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) }
            }
        }
        fail(EOFException("closed by the phone"))
    }

    private suspend fun write(opcode: Int, payload: ByteArray) {
        synchronized(lock) { failure?.let { throw it } }
        val streams = connection.await()
        val frame = frame(opcode, payload)
        withContext(Dispatchers.IO) {
            try {
                writeFrame(streams, frame)
            } catch (error: IOException) {
                fail(error)
                throw synchronized(lock) { failure } ?: error
            }
        }
    }

    // Senders and the reader's answers to pings and closes go out under
    // the stream's own lock, so two frames never interleave on the wire.
    private fun writeFrame(streams: Streams, frame: ByteArray) {
        synchronized(streams.output) {
            streams.output.write(frame)
            streams.output.flush()
        }
    }

    private fun handshake(): Streams {
        val socket = connector.connect(url)
        try {
            val output = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
            val host = url.host.let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
            val request = StringBuilder()
                .append("GET ").append(url.rawPath?.ifEmpty { null } ?: "/")
                .append(url.rawQuery?.let { "?$it" } ?: "").append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(if (url.port > 0) ":${url.port}" else "").append("\r\n")
                .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
            for ((name, value) in headers) request.append(name).append(": ").append(value).append("\r\n")
            request.append("\r\n")
            output.write(request.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            val status = readLine(input)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("not an HTTP answer: $status")
            val answer = HashMap<String, String>()
            var headerBytes = 0
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                headerBytes += line.length
                if (headerBytes > 16_384) throw IOException("the answer's headers are too long")
                val colon = line.indexOf(':')
                if (colon > 0) answer[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            if (code != 101) throw SocketRefused(code)
            if (answer["sec-websocket-accept"] != WebSocketConnector.acceptKey(key)) {
                throw IOException("the server's answer doesn't match this connection")
            }
            return Streams(socket, input, output)
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw EOFException("the connection closed while opening")
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
            if (line.length > 8_192) throw IOException("an answer line is too long")
        }
    }

    private fun readLoop(streams: Streams) {
        val input = streams.input
        var message: ByteArrayOutputStream? = null
        var messageIsText = false
        while (true) {
            val first = readByte(input)
            val second = readByte(input)
            val isFinal = first and 0x80 != 0
            val opcode = first and 0x0F
            if (second and 0x80 != 0) throw IOException("the server masked a frame")
            var length = (second and 0x7F).toLong()
            if (length == 126L) {
                length = (readByte(input).toLong() shl 8) or readByte(input).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte(input).toLong() }
            }
            if (length < 0 || length > WebSocketConnector.MAXIMUM_MESSAGE_BYTES) throw IOException("a message larger than 1 MB")
            val payload = readFully(input, length.toInt())
            when (opcode) {
                OPCODE_CONTINUATION -> {
                    val open = message ?: throw IOException("a continuation with no message")
                    open.write(payload)
                    if (open.size() > WebSocketConnector.MAXIMUM_MESSAGE_BYTES) throw IOException("a message larger than 1 MB")
                    if (isFinal) {
                        if (messageIsText) incoming.trySend(open.toByteArray().toString(Charsets.UTF_8))
                        message = null
                    }
                }
                OPCODE_TEXT, OPCODE_BINARY -> {
                    if (message != null) throw IOException("a new message inside another")
                    if (isFinal) {
                        if (opcode == OPCODE_TEXT) incoming.trySend(payload.toString(Charsets.UTF_8))
                    } else {
                        message = ByteArrayOutputStream().also { it.write(payload) }
                        messageIsText = opcode == OPCODE_TEXT
                    }
                }
                OPCODE_PING -> runCatching { writeFrame(streams, frame(OPCODE_PONG, payload)) }
                OPCODE_PONG -> synchronized(lock) { pongs.removeFirstOrNull() }?.complete(Unit)
                OPCODE_CLOSE -> {
                    val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                    val reason = if (payload.size > 2) payload.copyOfRange(2, payload.size).toString(Charsets.UTF_8) else ""
                    runCatching { writeFrame(streams, frame(OPCODE_CLOSE, payload.copyOfRange(0, minOf(payload.size, 2)))) }
                    throw SocketClosed(code, reason)
                }
                else -> throw IOException("an unknown frame type $opcode")
            }
        }
    }

    private fun readByte(input: InputStream): Int {
        val byte = input.read()
        if (byte < 0) throw EOFException("the server closed the connection")
        return byte
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val got = input.read(bytes, read, count - read)
            if (got < 0) throw EOFException("the server closed the connection")
            read += got
        }
        return bytes
    }

    private fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val mask = ByteArray(4).also(random::nextBytes)
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size < 65_536 -> {
                out.write(0x80 or 126)
                out.write(payload.size shr 8)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
            }
        }
        out.write(mask)
        out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        return out.toByteArray()
    }

    private fun fail(error: Throwable) {
        val waiting = synchronized(lock) {
            if (failure != null) return
            failure = error
            val pending = pongs.toList()
            pongs.clear()
            pending
        }
        waiting.forEach { it.completeExceptionally(error) }
        if (!connection.completeExceptionally(error)) {
            runCatching { connection.getCompleted().socket.close() }
        }
        incoming.close(error)
        scope.cancel()
    }

    private companion object {
        const val OPCODE_CONTINUATION = 0
        const val OPCODE_TEXT = 1
        const val OPCODE_BINARY = 2
        const val OPCODE_CLOSE = 8
        const val OPCODE_PING = 9
        const val OPCODE_PONG = 10
    }
}
