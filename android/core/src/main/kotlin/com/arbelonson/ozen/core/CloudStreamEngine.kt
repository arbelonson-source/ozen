package com.arbelonson.ozen.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun SonioxEngine(
    http: CloudHTTP = UrlConnectionCloudHTTP(),
    connector: CloudSocketConnecting,
    pingSeconds: Double = 5.0,
    pongSeconds: Double = 8.0,
    approvalSeconds: Double = CloudSpeechEngine.DEFAULT_APPROVAL_SECONDS,
    timeSource: TimeSource = TimeSource.Monotonic,
    apiKey: () -> String?,
): CloudStreamEngine = CloudStreamEngine(SonioxSpeech, http, connector, pingSeconds, pongSeconds, approvalSeconds, timeSource, apiKey)

fun SpeechmaticsEngine(
    http: CloudHTTP = UrlConnectionCloudHTTP(),
    connector: CloudSocketConnecting,
    pingSeconds: Double = 5.0,
    pongSeconds: Double = 8.0,
    approvalSeconds: Double = CloudSpeechEngine.DEFAULT_APPROVAL_SECONDS,
    timeSource: TimeSource = TimeSource.Monotonic,
    apiKey: () -> String?,
): CloudStreamEngine = CloudStreamEngine(SpeechmaticsSpeech, http, connector, pingSeconds, pongSeconds, approvalSeconds, timeSource, apiKey)

fun AssemblyAIEngine(
    http: CloudHTTP = UrlConnectionCloudHTTP(),
    connector: CloudSocketConnecting,
    pingSeconds: Double = 5.0,
    pongSeconds: Double = 8.0,
    approvalSeconds: Double = CloudSpeechEngine.DEFAULT_APPROVAL_SECONDS,
    timeSource: TimeSource = TimeSource.Monotonic,
    apiKey: () -> String?,
): CloudStreamEngine = CloudStreamEngine(AssemblyAISpeech, http, connector, pingSeconds, pongSeconds, approvalSeconds, timeSource, apiKey)

/**
 * Live captions from a service that streams (see `CloudStreamService`)
 * over one connection for the whole conversation, where the other cloud
 * services get a request per sentence: words come back while they are
 * said, and the service marks where each line ends and whose voice it is.
 *
 * A key the service turns down, or no credit, ends the stream with that
 * reason, which only a person can fix. A connection that drops, or stops
 * answering pings, ends it as no internet, which `CloudCover` answers by
 * carrying on with the phone's own model. Either way the line on screen
 * is marked cut, as the other engines mark theirs.
 *
 * The Swift engine is generic over the service; here the service is an
 * object passed in, and [SonioxEngine], [SpeechmaticsEngine] and
 * [AssemblyAIEngine] stand for its typealiases. A socket's receive is
 * expected to end when the calling coroutine is cancelled.
 */
class CloudStreamEngine(
    private val service: CloudStreamService,
    private val http: CloudHTTP = UrlConnectionCloudHTTP(),
    private val connector: CloudSocketConnecting,
    private val pingSeconds: Double = 5.0,
    private val pongSeconds: Double = 8.0,
    private val approvalSeconds: Double = CloudSpeechEngine.DEFAULT_APPROVAL_SECONDS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val apiKey: () -> String?,
) : TranscriptionEngine {
    override val kind: TranscriptionEngineKind = TranscriptionEngineKind.Cloud
    val provider: CloudProvider = service.provider
    val model: String = service.model

    private val lock = Any()
    private var vocabulary: List<String> = emptyList()

    /**
     * The key the last check approved, trusted for `approvalSeconds`
     * after the check or the last reply, as `CloudSpeechEngine` trusts
     * its own: the checks for whether the cloud is back use this engine.
     */
    private var approvedKey: String? = null
    private var approvedAt: TimeMark? = null
    private var endSent = false
    private var pingSentAt: TimeMark? = null
    private var pongLost = false

    /**
     * The service takes the names list with the settings that open the
     * connection, so a change made while captions run is used from the
     * next start.
     */
    override suspend fun setVocabulary(terms: List<String>) {
        synchronized(lock) { vocabulary = terms }
    }

    override suspend fun prepare(languageCode: String, progress: (EnginePreparationProgress) -> Unit): EngineAvailability {
        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.CheckingSupport))
        val key = currentKey() ?: return EngineAvailability.Unavailable(CloudSpeechError.KeyMissing.unavailability)
        val trusted = synchronized(lock) {
            val at = approvedAt
            approvedKey == key && at != null && at.elapsedNow() < approvalSeconds.seconds
        }
        if (trusted) return EngineAvailability.Available
        val response = try {
            http.send(service.keyCheckRequest(key))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return EngineAvailability.Unavailable(CloudSpeechError.Offline.unavailability)
        }
        if (response.status !in 200 until 300) {
            return EngineAvailability.Unavailable(service.failure(response).unavailability)
        }
        synchronized(lock) {
            approvedKey = key
            approvedAt = timeSource.markNow()
        }
        return EngineAvailability.Available
    }

    override fun stream(languageCode: String, audio: Flow<FloatArray>): Flow<TranscriptToken> =
        tokenFlow { tokens -> run(languageCode, audio, tokens) }

    private fun currentKey(): String? {
        val key = apiKey()?.let { trimmingWhitespaceAndNewlines(it) }
        return if (key.isNullOrEmpty()) null else key
    }

    private fun currentVocabulary(): List<String> = synchronized(lock) { vocabulary }

    private suspend fun run(languageCode: String, audio: Flow<FloatArray>, tokens: SendChannel<TranscriptToken>) {
        val key = currentKey() ?: throw CloudSpeechError.KeyMissing
        val socket = try {
            connector.open(service.address(languageCode, currentVocabulary()), service.headers(key))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw CloudSpeechError.Offline
        }
        synchronized(lock) {
            endSent = false
            pongLost = false
            pingSentAt = null
        }
        val settings = service.config(languageCode, currentVocabulary())
        if (settings.isNotEmpty()) {
            try {
                socket.send(settings)
            } catch (error: CancellationException) {
                withContext(NonCancellable) { socket.close() }
                throw error
            } catch (error: Exception) {
                withContext(NonCancellable) { socket.close() }
                throw failure(error)
            }
        }
        val started = CompletableDeferred<Unit>()
        if (!service.waitsForStart) started.complete(Unit)
        // Not children of this call, as the Swift engine's tasks are not: a
        // send that does not end when cancelled must not hold the stream up.
        val workers = CoroutineScope(currentCoroutineContext().minusKey(Job))
        val sender = workers.launch {
            started.await()
            var chunks = 0
            // The microphone hands over about 43 ms at a time; AssemblyAI
            // closes a session over a piece shorter than 50 ms or longer
            // than a second (3007), so the audio goes in pieces between.
            val pieces = CloudAudioFrames()
            audio.collect { chunk ->
                ensureActive()
                for (piece in pieces.add(chunk)) {
                    sendIgnoringFailure { socket.send(HomeServer.pcm16(piece)) }
                    chunks += 1
                }
            }
            // A stream that was stopped never ended its audio, and its
            // engine may already be running the next one.
            if (!isActive) return@launch
            val last = pieces.finish()
            if (last != null) {
                sendIgnoringFailure { socket.send(HomeServer.pcm16(last)) }
                chunks += 1
            }
            // Marked before it is sent: the service may close the
            // connection as soon as it has answered the last words.
            markEndSent()
            sendIgnoringFailure { socket.send(service.endMessage(chunks)) }
        }
        val heartbeat = workers.launch {
            var nextCheck = pingSeconds.seconds
            while (isActive) {
                delay(nextCheck)
                nextCheck = pingSeconds.seconds
                when (val step = heartbeatStep()) {
                    is HeartbeatStep.Wait -> nextCheck = minOf(nextCheck, step.overdueIn)
                    HeartbeatStep.Lost -> {
                        withContext(NonCancellable) { socket.close() }
                        return@launch
                    }
                    HeartbeatStep.Ping -> workers.launch {
                        sendIgnoringFailure {
                            socket.ping()
                            notePong()
                        }
                    }
                }
            }
        }
        try {
            receive(socket, key, languageCode, started, tokens)
        } finally {
            sender.cancel()
            heartbeat.cancel()
        }
    }

    private suspend fun receive(
        socket: HomeServerSocket,
        key: String,
        languageCode: String,
        started: CompletableDeferred<Unit>,
        tokens: SendChannel<TranscriptToken>,
    ) {
        val lines = CloudStreamLines(spaced = CloudStreamLines.spaced(languageCode))
        while (true) {
            val frame = try {
                socket.receive()
            } catch (error: Exception) {
                withContext(NonCancellable) { socket.close() }
                // Stopping captions leaves the lines as they are.
                if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                val ended = synchronized(lock) { endSent && !pongLost }
                if (ended) {
                    lines.finish(nowSeconds()).forEach { tokens.trySend(it) }
                    return
                }
                lines.cutOff(nowSeconds())?.let { tokens.trySend(it) }
                // Asked again for real next time: the pipeline checks
                // whether the cloud is back.
                forgetApproval()
                throw failure(error)
            }
            val reply = service.reply(frame, languageCode) ?: continue
            synchronized(lock) { if (approvedKey == key) approvedAt = timeSource.markNow() }
            val now = nowSeconds()
            when (reply) {
                CloudStreamReply.Started -> started.complete(Unit)
                is CloudStreamReply.Failure -> {
                    lines.cutOff(now)?.let { tokens.trySend(it) }
                    if (reply.error.needsPerson) forgetApproval()
                    withContext(NonCancellable) { socket.close() }
                    throw reply.error
                }
                is CloudStreamReply.Tokens -> {
                    lines.take(reply.tokens, now).forEach { tokens.trySend(it) }
                    if (reply.finished) {
                        lines.finish(now).forEach { tokens.trySend(it) }
                        withContext(NonCancellable) { socket.close() }
                        return
                    }
                }
            }
        }
    }

    /**
     * What a connection that failed means: the service's own reason when
     * it refused to open it or closed it with a code, else no internet.
     */
    private fun failure(error: Throwable): CloudSpeechError {
        if (error is SocketRefused) {
            return service.failure(CloudHTTPResponse(error.status, ByteArray(0)))
        }
        if (error is SocketClosed) {
            val failure = service.failure(error.code, error.reason)
            if (failure != null) return failure
        }
        return CloudSpeechError.Offline
    }

    private fun forgetApproval() {
        synchronized(lock) { approvedKey = null }
    }

    private fun markEndSent() {
        synchronized(lock) { endSent = true }
    }

    /**
     * A connection that died without closing (Wi-Fi gone under a router
     * that never says so) sends no error, and in a quiet room there are
     * no words to miss. A ping left unanswered for `pongSeconds` means
     * the path is gone; a ping is only sent once the last one was
     * answered.
     */
    private fun heartbeatStep(): HeartbeatStep = synchronized(lock) {
        val sent = pingSentAt
        if (sent == null) {
            pingSentAt = timeSource.markNow()
            return@synchronized HeartbeatStep.Ping
        }
        val overdueIn = pongSeconds.seconds - sent.elapsedNow()
        if (overdueIn > Duration.ZERO) return@synchronized HeartbeatStep.Wait(overdueIn)
        pongLost = true
        HeartbeatStep.Lost
    }

    private sealed class HeartbeatStep {
        data object Ping : HeartbeatStep()

        data class Wait(val overdueIn: Duration) : HeartbeatStep()

        data object Lost : HeartbeatStep()
    }

    private fun notePong() {
        synchronized(lock) { pingSentAt = null }
    }
}

private suspend fun sendIgnoringFailure(send: suspend () -> Unit) {
    try {
        send()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
    }
}

private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

private fun trimmingWhitespaceAndNewlines(text: String): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    fun isSpace(scalar: Int) = when (Character.getType(scalar).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> scalar in 0x09..0x0D || scalar == 0x85
    }
    while (from < to && isSpace(scalars[from])) from++
    while (to > from && isSpace(scalars[to - 1])) to--
    return String(scalars, from, to - from)
}

private fun tokenFlow(run: suspend (SendChannel<TranscriptToken>) -> Unit): Flow<TranscriptToken> = flow {
    val tokens = Channel<TranscriptToken>(Channel.UNLIMITED)
    coroutineScope {
        val producer = launch {
            try {
                run(tokens)
                tokens.close()
            } catch (error: CancellationException) {
                tokens.close()
                throw error
            } catch (error: Throwable) {
                tokens.close(error)
            }
        }
        try {
            for (token in tokens) emit(token)
        } finally {
            producer.cancel()
        }
    }
}
