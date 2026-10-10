package com.arbelonson.ozen.core

import java.io.IOException
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class FakeCloudHTTP(
    answers: List<Answer> = emptyList(),
    keyChecks: List<Answer> = emptyList(),
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : CloudHTTP {
    sealed class Answer {
        class Text(val text: String) : Answer()

        class Status(val status: Int, val body: String) : Answer()

        data object Offline : Answer()
    }

    private val lock = Any()
    private val answers = answers.toMutableList()
    private val keyChecks = keyChecks.toMutableList()
    private val sent = ArrayList<CloudHTTPRequest>()
    private val transcriptionTimes = ArrayList<ComparableTimeMark>()

    val requests: List<CloudHTTPRequest> get() = synchronized(lock) { sent.toList() }
    val transcriptionRequests: List<CloudHTTPRequest> get() = requests.filter { it.method == "POST" }
    val transcriptionSentAt: List<ComparableTimeMark> get() = synchronized(lock) { transcriptionTimes.toList() }

    override suspend fun send(request: CloudHTTPRequest): CloudHTTPResponse {
        val answer: Answer = synchronized(lock) {
            sent.add(request)
            if (request.method == "POST") transcriptionTimes.add(timeSource.markNow())
            if (request.method == "GET") {
                if (keyChecks.isEmpty()) Answer.Status(200, """{"data":{"limit_remaining":null}}""") else keyChecks.removeAt(0)
            } else if (answers.size > 1) {
                answers.removeAt(0)
            } else {
                answers.firstOrNull() ?: Answer.Text("")
            }
        }
        return when (answer) {
            is Answer.Text -> {
                val message = JsonObject(mapOf("role" to JsonPrimitive("assistant"), "content" to JsonPrimitive(answer.text)))
                val reply = JsonObject(mapOf("choices" to JsonArray(listOf(JsonObject(mapOf("message" to message))))))
                CloudHTTPResponse(200, reply.toString().toByteArray(Charsets.UTF_8))
            }
            is Answer.Status -> CloudHTTPResponse(answer.status, answer.body.toByteArray(Charsets.UTF_8))
            Answer.Offline -> throw IOException("not connected to the internet")
        }
    }
}

internal class KeyBox(value: String?) {
    private val lock = Any()
    private var stored: String? = value

    var value: String?
        get() = synchronized(lock) { stored }
        set(newValue) = synchronized(lock) { stored = newValue }
}

internal class StreamGone : Exception("stream gone")

internal class StreamSocket(
    private val onConfig: List<String> = emptyList(),
    private val afterEnd: List<String> = emptyList(),
    answersPings: Boolean = true,
    private val holdsAudio: Boolean = false,
    refusing: Int? = null,
    private val endsAudio: (String) -> Boolean = { it == SonioxSpeech.END },
) : HomeServerSocket {
    private val lock = Any()
    private val answersPings = answersPings
    private val refusal: SocketRefused? = refusing?.let { SocketRefused(it) }
    private val heldSends = ArrayList<CompletableDeferred<Unit>>()
    private val sentTextList = ArrayList<String>()
    private val attemptedTextList = ArrayList<String>()
    private val sentChunkByteList = ArrayList<Int>()
    private var sentByteCount = 0
    private var closed = false
    private var pingCount = 0
    private val queue = ArrayDeque<String>()
    private val waiters = ArrayList<CompletableDeferred<String>>()
    private val pingWaiters = ArrayList<CompletableDeferred<Unit>>()
    private var closedWith: Throwable = StreamGone()

    val sentTexts: List<String> get() = synchronized(lock) { sentTextList.toList() }

    /** Every text the engine tried to send, after the close too. */
    val attemptedTexts: List<String> get() = synchronized(lock) { attemptedTextList.toList() }
    val sentBytes: Int get() = synchronized(lock) { sentByteCount }
    val sentChunks: Int get() = synchronized(lock) { sentChunkByteList.size }
    val sentChunkBytes: List<Int> get() = synchronized(lock) { sentChunkByteList.toList() }
    val isClosed: Boolean get() = synchronized(lock) { closed }
    val pings: Int get() = synchronized(lock) { pingCount }
    val heldAudio: Int get() = synchronized(lock) { heldSends.size }

    fun releaseAudio() {
        val held = synchronized(lock) {
            val copy = heldSends.toList()
            heldSends.clear()
            copy
        }
        held.forEach { it.complete(Unit) }
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

    /** Closes the connection as the service would, with [error] as the reason the engine is given. */
    fun drop(error: Throwable? = null) {
        val pendingWaiters: List<CompletableDeferred<String>>
        val pendingPings: List<CompletableDeferred<Unit>>
        val reason: Throwable
        synchronized(lock) {
            if (error != null && !closed) closedWith = error
            closed = true
            pendingWaiters = waiters.toList()
            waiters.clear()
            pendingPings = pingWaiters.toList()
            pingWaiters.clear()
            reason = closedWith
        }
        pendingWaiters.forEach { it.completeExceptionally(reason) }
        pendingPings.forEach { it.completeExceptionally(StreamGone()) }
    }

    override suspend fun send(text: String) {
        val first: Boolean
        synchronized(lock) {
            attemptedTextList.add(text)
            if (refusal != null) throw refusal
            if (closed) throw StreamGone()
            sentTextList.add(text)
            first = sentTextList.size == 1
        }
        if (first) onConfig.forEach { deliver(it) }
        if (endsAudio(text)) {
            afterEnd.forEach { deliver(it) }
            drop()
        }
    }

    override suspend fun send(data: ByteArray) {
        var hold: CompletableDeferred<Unit>? = null
        synchronized(lock) {
            if (refusal != null) throw refusal
            if (closed) throw StreamGone()
            if (holdsAudio) {
                hold = CompletableDeferred<Unit>().also { heldSends.add(it) }
            }
        }
        hold?.let { held -> withContext(NonCancellable) { held.await() } }
        synchronized(lock) {
            sentByteCount += data.size
            sentChunkByteList.add(data.size)
        }
    }

    override suspend fun receive(): String {
        val waiter = CompletableDeferred<String>()
        synchronized(lock) {
            if (refusal != null) throw refusal
            if (queue.isNotEmpty()) return queue.removeFirst()
            if (closed) throw closedWith
            waiters.add(waiter)
        }
        try {
            return waiter.await()
        } catch (error: CancellationException) {
            synchronized(lock) { waiters.remove(waiter) }
            throw error
        }
    }

    override suspend fun ping() {
        val waiter = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (closed) throw StreamGone()
            pingCount += 1
            if (answersPings) return
            pingWaiters.add(waiter)
        }
        try {
            waiter.await()
        } catch (error: CancellationException) {
            synchronized(lock) { pingWaiters.remove(waiter) }
            throw error
        }
    }

    override suspend fun close() {
        drop()
    }
}

internal class StreamDialer(private val socket: StreamSocket?) : CloudSocketConnecting {
    class Call(val url: URI, val headers: Map<String, String>)

    private val lock = Any()
    private val opened = ArrayList<Call>()

    val calls: List<Call> get() = synchronized(lock) { opened.toList() }

    override suspend fun open(url: URI, headers: Map<String, String>): HomeServerSocket {
        synchronized(lock) { opened.add(Call(url, headers)) }
        return socket ?: throw StreamGone()
    }
}

internal class StreamHeard {
    val tokens = ArrayList<TranscriptToken>()
    var error: Throwable? = null
}

internal fun streamAudio(chunks: Int, ends: Boolean = true): Flow<FloatArray> {
    val channel = Channel<FloatArray>(Channel.UNLIMITED)
    repeat(chunks) { channel.trySend(FloatArray(1_600) { 0.05f }) }
    if (ends) channel.close()
    return channel.receiveAsFlow()
}

internal suspend fun listen(
    engine: TranscriptionEngine,
    audio: Flow<FloatArray>,
    languageCode: String = "he",
    heard: StreamHeard = StreamHeard(),
    afterFirst: (suspend () -> Unit)? = null,
): StreamHeard {
    try {
        engine.stream(languageCode, audio).collect { token ->
            heard.tokens.add(token)
            if (heard.tokens.size == 1 && afterFirst != null) afterFirst()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        heard.error = error
    }
    return heard
}

internal suspend fun TestScope.waitUntil(withinSeconds: Double = 10.0, check: suspend () -> Boolean): Boolean {
    val deadline = testScheduler.timeSource.markNow() + withinSeconds.seconds
    runCurrent()
    while (!check()) {
        if (deadline.hasPassedNow()) return false
        advanceTimeBy(5)
        runCurrent()
    }
    return true
}

internal suspend fun TestScope.eventually(withinSeconds: Double = 10.0, condition: () -> Boolean): Boolean =
    waitUntil(withinSeconds) { condition() }

internal class AudioFeed {
    private val channel = Channel<FloatArray>(Channel.UNLIMITED)
    val flow: Flow<FloatArray> = channel.receiveAsFlow()

    fun add(chunks: List<FloatArray>) {
        chunks.forEach { channel.trySend(it) }
    }

    fun finish() {
        channel.close()
    }
}

internal class Collected {
    val tokens = ArrayList<TranscriptToken>()
    var error: Throwable? = null
    lateinit var job: kotlinx.coroutines.Job
}

internal fun TestScope.collecting(flow: Flow<TranscriptToken>): Collected {
    val collected = Collected()
    collected.job = launch {
        try {
            flow.collect { collected.tokens.add(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            collected.error = error
        }
    }
    return collected
}
