package com.arbelonson.ozen.core

import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred

internal class ScriptedClosed : Exception("closed")

/**
 * A server that follows a script: what it says to the hello, and what it
 * says once the phone reports the end of its audio.
 */
internal class ScriptedSocket(
    private val helloReply: String?,
    private val afterEnd: List<String> = emptyList(),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : HomeServerSocket {
    private val lock = Any()
    private val sentTextList = ArrayList<String>()
    private var sentByteCount = 0
    private var closed = false
    private var pingCount = 0
    private var firstPing: ComparableTimeMark? = null
    private var reportReply: String? = null
    private var answers = true
    private var hangs = false
    private val queue = ArrayDeque<String>()
    private val waiters = ArrayList<CompletableDeferred<String>>()
    private val pingWaiters = ArrayList<CompletableDeferred<Unit>>()
    private val helloSends = ArrayList<CompletableDeferred<Unit>>()

    val sentTexts: List<String> get() = synchronized(lock) { sentTextList.toList() }
    val sentBytes: Int get() = synchronized(lock) { sentByteCount }
    val isClosed: Boolean get() = synchronized(lock) { closed }
    val pings: Int get() = synchronized(lock) { pingCount }
    val firstPingAt: ComparableTimeMark? get() = synchronized(lock) { firstPing }

    fun setAnswersPings(answers: Boolean) {
        synchronized(lock) { this.answers = answers }
    }

    fun setHangsOnHello(hangs: Boolean) {
        synchronized(lock) { this.hangs = hangs }
    }

    fun setReportReply(reply: String?) {
        synchronized(lock) { reportReply = reply }
    }

    fun deliver(frame: String) {
        val waiter = synchronized(lock) {
            if (waiters.isEmpty()) {
                queue.addLast(frame)
                null
            } else {
                waiters.removeAt(0)
            }
        }
        waiter?.complete(frame)
    }

    fun drop() {
        val pendingWaiters: List<CompletableDeferred<String>>
        val pendingPings: List<CompletableDeferred<Unit>>
        val pendingSends: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            closed = true
            pendingWaiters = waiters.toList()
            waiters.clear()
            pendingPings = pingWaiters.toList()
            pingWaiters.clear()
            pendingSends = helloSends.toList()
            helloSends.clear()
        }
        pendingWaiters.forEach { it.completeExceptionally(ScriptedClosed()) }
        pendingPings.forEach { it.completeExceptionally(ScriptedClosed()) }
        pendingSends.forEach { it.completeExceptionally(ScriptedClosed()) }
    }

    override suspend fun ping() {
        val waiter = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (closed) throw ScriptedClosed()
            pingCount += 1
            if (firstPing == null) firstPing = timeSource.markNow()
            if (answers) return
            pingWaiters.add(waiter)
        }
        try {
            waiter.await()
        } catch (error: CancellationException) {
            synchronized(lock) { pingWaiters.remove(waiter) }
            throw error
        }
    }

    override suspend fun send(text: String) {
        val hello = text.contains(""""type":"hello"""")
        var hold: CompletableDeferred<Unit>? = null
        synchronized(lock) {
            if (closed) throw ScriptedClosed()
            if (hangs && hello) {
                hold = CompletableDeferred<Unit>().also { helloSends.add(it) }
            }
        }
        hold?.let { pending ->
            // Like a connection that never completes: only closing the
            // socket (or cancelling) ends the wait.
            try {
                pending.await()
            } catch (error: CancellationException) {
                drop()
                throw error
            }
        }
        synchronized(lock) { sentTextList.add(text) }
        if (hello && helloReply != null) deliver(helloReply)
        val report = synchronized(lock) { reportReply }
        if (text.contains(""""type":"report"""") && report != null) deliver(report)
        if (text == HomeServer.END) {
            afterEnd.forEach { deliver(it) }
            drop()
        }
    }

    override suspend fun send(data: ByteArray) {
        synchronized(lock) {
            if (closed) throw ScriptedClosed()
            sentByteCount += data.size
        }
    }

    override suspend fun receive(): String {
        val waiter = CompletableDeferred<String>()
        synchronized(lock) {
            if (queue.isNotEmpty()) return queue.removeFirst()
            if (closed) throw ScriptedClosed()
            waiters.add(waiter)
        }
        try {
            return waiter.await()
        } catch (error: CancellationException) {
            synchronized(lock) { waiters.remove(waiter) }
            throw error
        }
    }

    override suspend fun close() {
        drop()
    }
}

internal class ScriptedConnector(private val socket: ScriptedSocket?) : HomeServerConnecting {
    override suspend fun open(url: URI): HomeServerSocket = socket ?: throw ScriptedClosed()
}

internal const val SCRIPTED_READY = """{"type":"ready","model":"ivrit","version":1}"""

internal fun scriptedText(utterance: Int, words: String, final: Boolean): String =
    """{"type":"text","utterance":$utterance,"text":"$words","final":$final,"confidence":0.9}"""
