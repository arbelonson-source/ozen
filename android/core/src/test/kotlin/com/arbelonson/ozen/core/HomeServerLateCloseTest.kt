package com.arbelonson.ozen.core

import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private class Gone : Exception("gone")

private class SlowCloseSocket : HomeServerSocket {
    private val lock = Any()
    private val sentTextList = ArrayList<String>()
    private var sentByteCount = 0
    private var closeWasRequested = false
    private var receiveHasEnded = false
    private var released = false
    private val queue = ArrayDeque<String>()
    private var waiter: CompletableDeferred<String>? = null

    val sentTexts: List<String> get() = synchronized(lock) { sentTextList.toList() }
    val sentBytes: Int get() = synchronized(lock) { sentByteCount }
    val closeRequested: Boolean get() = synchronized(lock) { closeWasRequested }
    val receiveEnded: Boolean get() = synchronized(lock) { receiveHasEnded }

    override suspend fun send(text: String) {
        synchronized(lock) { sentTextList.add(text) }
        if (text.contains(""""type":"hello"""")) deliver("""{"type":"ready","model":"m","version":1}""")
    }

    override suspend fun send(data: ByteArray) {
        synchronized(lock) { sentByteCount += data.size }
    }

    override suspend fun receive(): String {
        val pending = CompletableDeferred<String>()
        synchronized(lock) {
            if (queue.isNotEmpty()) return queue.removeFirst()
            if (released) {
                receiveHasEnded = true
                throw Gone()
            }
            waiter = pending
        }
        return try {
            // Like a receive that ignores cancellation: only release ends it.
            withContext(NonCancellable) { pending.await() }
        } catch (error: CancellationException) {
            throw error
        }
    }

    override suspend fun ping() {}

    override suspend fun close() {
        synchronized(lock) { closeWasRequested = true }
    }

    fun release() {
        val pending = synchronized(lock) {
            released = true
            val current = waiter
            waiter = null
            if (current != null) receiveHasEnded = true
            current
        }
        pending?.completeExceptionally(Gone())
    }

    private fun deliver(frame: String) {
        val pending = synchronized(lock) {
            val current = waiter
            waiter = null
            if (current == null) queue.addLast(frame)
            current
        }
        pending?.complete(frame)
    }
}

/** A computer that closes the connection the moment the phone says it is done, before the phone's own send of that has finished. */
private class CloseOnEndSocket : HomeServerSocket {
    private val lock = Any()
    private val sentTextList = ArrayList<String>()
    private val queue = ArrayDeque<String>()
    private var closed = false
    private var waiter: CompletableDeferred<String>? = null

    val sentTexts: List<String> get() = synchronized(lock) { sentTextList.toList() }

    override suspend fun send(text: String) {
        synchronized(lock) { sentTextList.add(text) }
        if (text.contains(""""type":"hello"""")) deliver("""{"type":"ready","model":"m","version":1}""")
        if (text == HomeServer.END) {
            val pending = synchronized(lock) {
                closed = true
                val current = waiter
                waiter = null
                current
            }
            pending?.completeExceptionally(Gone())
            delay(100)
        }
    }

    override suspend fun send(data: ByteArray) {}

    override suspend fun receive(): String {
        val pending = CompletableDeferred<String>()
        synchronized(lock) {
            if (queue.isNotEmpty()) return queue.removeFirst()
            if (closed) throw Gone()
            waiter = pending
        }
        return pending.await()
    }

    override suspend fun ping() {}

    override suspend fun close() {}

    private fun deliver(frame: String) {
        val pending = synchronized(lock) {
            val current = waiter
            waiter = null
            if (current == null) queue.addLast(frame)
            current
        }
        pending?.complete(frame)
    }
}

private class Sockets(sockets: List<HomeServerSocket>) : HomeServerConnecting {
    private val lock = Any()
    private val pending = sockets.toMutableList()

    override suspend fun open(url: URI): HomeServerSocket = synchronized(lock) { pending.removeAt(0) }
}

class HomeServerLateCloseTest {
    @Test
    fun `a name added after a restart reaches the new connection even when the old one finishes closing late`() = runTest {
        val old = SlowCloseSocket()
        val fresh = SlowCloseSocket()
        val engine = HomeServerEngine(
            address = "10.0.0.5",
            token = { "1234" },
            connector = Sockets(listOf(old, fresh)),
            handshakeSeconds = 2.0,
            timeSource = testScheduler.timeSource,
        )
        val audio1 = AudioFeed()
        val first = launch {
            try {
                engine.stream("he", audio1.flow).collect { }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
            }
        }
        assertTrue(waitUntil(5.0) { old.sentTexts.any { it.contains(""""type":"hello"""") } })
        engine.setVocabulary(listOf("warmup"))
        assertTrue(waitUntil(5.0) { old.sentTexts.any { it.contains("vocabulary") } })
        first.cancel()
        assertTrue(waitUntil(5.0) { old.closeRequested })

        val audio2 = AudioFeed()
        val second = launch {
            try {
                engine.stream("he", audio2.flow).collect { }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
            }
        }
        audio2.add(listOf(FloatArray(160)))
        assertTrue(waitUntil(5.0) { fresh.sentBytes > 0 })

        old.release()
        assertTrue(waitUntil(5.0) { old.receiveEnded })
        repeat(50) {
            yield()
            engine.diagnosticsSummary()
        }
        engine.setVocabulary(listOf("LateName"))
        val reached = waitUntil(5.0) { fresh.sentTexts.any { it.contains("LateName") } }
        assertTrue(reached, "the new connection never got the name added after the old run ended")
        second.cancel()
        fresh.release()
    }

    @Test
    fun `a computer that closes the moment the phone says it's done isn't taken for a lost connection`() = runTest {
        val socket = CloseOnEndSocket()
        val engine = HomeServerEngine(
            address = "10.0.0.5",
            token = { "1234" },
            connector = Sockets(listOf(socket)),
            handshakeSeconds = 2.0,
            timeSource = testScheduler.timeSource,
        )
        val audio = AudioFeed()
        val run = collecting(engine.stream("he", audio.flow))
        assertTrue(waitUntil(5.0) { socket.sentTexts.any { it.contains(""""type":"hello"""") } })
        audio.add(listOf(FloatArray(160)))
        audio.finish()
        run.job.join()
        assertNull(run.error, "the session ended with ${run.error}")
    }
}
