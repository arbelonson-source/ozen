package com.arbelonson.ozen.core

import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Live captions from the family's own GPU computer (see `HomeServer`).
 * The server decides where lines start and end, exactly as the phone's
 * Whisper engine would; this side only streams the microphone and turns
 * each reply into a token for the line it belongs to.
 *
 * Anything that stops the server being reachable (no answer to the
 * hello, the connection dropping mid-sentence) ends the stream with
 * `homeServerUnreachable`, which `CloudCover` answers by carrying on with
 * the phone's own model; a pairing code the server turns down ends it
 * with `homeServerRejected`, which only a person can fix.
 *
 * The state a Swift actor would guard is behind one lock that is never
 * held across a suspension. [timeSource] measures the approval, the
 * pings and the handshake; tests hand in the test scheduler's. A socket's
 * calls are expected to end when the calling coroutine is cancelled.
 */
class HomeServerEngine(
    val address: String,
    private val token: () -> String?,
    private val connector: HomeServerConnecting,
    private val handshakeSeconds: Double = 5.0,
    private val client: String = "",
    private val stallSeconds: Double = 35.0,
    private val pingSeconds: Double = 5.0,
    private val pongSeconds: Double = 8.0,
    private val beam: Int? = null,
    private val approvalSeconds: Double = DEFAULT_APPROVAL_SECONDS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : TranscriptionEngine {
    override val kind: TranscriptionEngineKind = TranscriptionEngineKind.HomeServer

    private class Target(val url: URI, val token: String)

    private val lock = Any()
    private var speechDetector = EnergyVoiceDetector.forWhisperLines()
    private var samplesSent = 0
    private var speechSinceReply: Int? = null
    private var stalled = false
    private var pingSentAt: TimeMark? = null
    private var pongLost = false
    private var vocabulary: List<String> = emptyList()
    private var echo: PromptEchoDetector? = null
    private val filter = WhisperResultFilter()
    private var liveSocket: HomeServerSocket? = null

    /** Which run `liveSocket` belongs to, so only that run unhooks it. */
    private var liveSocketRun: UUID? = null
    private var verified: Target? = null

    /**
     * The last time the computer answered anything. An approval older
     * than `approvalSeconds` is checked again: trusted the next morning,
     * a computer that went to sleep overnight showed "Listening" for the
     * handshake's 5 s, and what was said then was thrown away when the
     * phone's model took over. Shorter than the minute between switch-back
     * checks (`CaptionPipeline.homeServerRecheckSeconds`), so each check
     * really asks the computer and the restart right after one trusts a
     * fresh answer, not one from a check a minute before.
     */
    private var lastHeardAt: TimeMark? = null
    private var endSent = false

    override suspend fun setVocabulary(terms: List<String>) {
        val detector = PromptEchoDetector(terms)
        val socket = synchronized(lock) {
            vocabulary = terms
            echo = if (detector.isEmpty) null else detector
            liveSocket
        }
        if (socket != null) {
            sendIgnoringFailure { socket.send(HomeServer.vocabularyUpdate(terms)) }
        }
    }

    override suspend fun prepare(languageCode: String, progress: (EnginePreparationProgress) -> Unit): EngineAvailability {
        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.CheckingSupport))
        val target = try {
            destination()
        } catch (why: EngineUnavailability) {
            return EngineAvailability.Unavailable(why)
        }
        val trusted = synchronized(lock) {
            val known = verified
            val heard = lastHeardAt
            known != null && known.url == target.url && known.token == target.token &&
                heard != null && heard.elapsedNow() < approvalSeconds.seconds
        }
        if (trusted) return EngineAvailability.Available
        return try {
            val socket = handshake(target, languageCode, "check", currentVocabulary())
            withContext(NonCancellable) { socket.close() }
            synchronized(lock) {
                verified = target
                lastHeardAt = timeSource.markNow()
            }
            EngineAvailability.Available
        } catch (error: CancellationException) {
            throw error
        } catch (why: EngineUnavailability) {
            EngineAvailability.Unavailable(why)
        } catch (error: Exception) {
            EngineAvailability.Unavailable(EngineUnavailability.homeServerUnreachable("$error"))
        }
    }

    override fun stream(languageCode: String, audio: Flow<FloatArray>): Flow<TranscriptToken> =
        tokenFlow { tokens -> run(languageCode, audio, tokens) }

    /**
     * Sends a diagnostics report to the server, which keeps it in its
     * reports folder. True once the server says it was saved.
     */
    suspend fun sendReport(text: String, languageCode: String): Boolean {
        val target = try {
            destination()
        } catch (why: EngineUnavailability) {
            return false
        }
        val socket = try {
            handshake(target, languageCode, "report", currentVocabulary())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return false
        }
        try {
            try {
                socket.send(HomeServer.report(text))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return false
            }
            val reply = try {
                firstReply(socket, handshakeSeconds)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return false
            }
            return HomeServerMessage.parse(reply) is HomeServerMessage.ReportSaved
        } finally {
            withContext(NonCancellable) { socket.close() }
        }
    }

    override suspend fun diagnosticsSummary(): String? = "home server $address"

    private fun currentVocabulary(): List<String> = synchronized(lock) { vocabulary }

    private fun forgetVerified() {
        synchronized(lock) { verified = null }
    }

    // MARK: - Streaming

    private suspend fun run(languageCode: String, audio: Flow<FloatArray>, tokens: SendChannel<TranscriptToken>) {
        val target = destination()
        val helloVocabulary = currentVocabulary()
        val socket = try {
            handshake(target, languageCode, "captions", helloVocabulary)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            forgetVerified()
            throw (error as? EngineUnavailability) ?: EngineUnavailability.homeServerUnreachable("$error")
        }
        val run = UUID.randomUUID()
        val changed = synchronized(lock) {
            liveSocket = socket
            liveSocketRun = run
            lastHeardAt = timeSource.markNow()
            vocabulary != helloVocabulary
        }
        // A name added while the hello waited for its answer found no live
        // socket to send on; the server would keep the old list all session.
        if (changed) {
            sendIgnoringFailure { socket.send(HomeServer.vocabularyUpdate(currentVocabulary())) }
        }
        synchronized(lock) {
            endSent = false
            stalled = false
            pongLost = false
            pingSentAt = null
            samplesSent = 0
            speechSinceReply = null
            speechDetector = EnergyVoiceDetector.forWhisperLines()
        }
        // Not children of this call, as the Swift engine's tasks are not: a
        // send that does not end when cancelled must not hold the stream up.
        val workers = CoroutineScope(currentCoroutineContext().minusKey(Job))
        val sender = workers.launch {
            var stopped = false
            audio.takeWhile { chunk ->
                ensureActive()
                sendIgnoringFailure { socket.send(HomeServer.pcm16(chunk)) }
                if (noteSent(chunk)) {
                    stopped = true
                    false
                } else {
                    true
                }
            }.collect { }
            if (stopped) {
                withContext(NonCancellable) { socket.close() }
                return@launch
            }
            // Marked before it is sent, as the cloud engine does: the server
            // closes the connection as soon as it has answered the last words.
            markEndSent()
            sendIgnoringFailure { socket.send(HomeServer.END) }
        }
        val heartbeat = workers.launch {
            var nextCheck = pingSeconds.seconds
            while (isActive) {
                delay(nextCheck)
                nextCheck = pingSeconds.seconds
                when (val step = heartbeatStep()) {
                    is HeartbeatStep.Wait -> {
                        // Checked again when the answer is overdue, not a whole
                        // ping later: that made 8 s mean 10, and 15 for a
                        // computer that hung just after answering.
                        nextCheck = minOf(nextCheck, step.overdueIn)
                    }
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
        // Stopping captions cancels this call, but a socket's receive
        // doesn't notice cancellation: without the close the connection
        // (and the receive loop) would stay open for as long as the server did.
        val stopping = AtomicBoolean(false)
        val receiver = workers.async { receive(socket, run, tokens, stopping) }
        try {
            receiver.await()
        } catch (error: CancellationException) {
            stopping.set(true)
            withContext(NonCancellable) { socket.close() }
            throw error
        } finally {
            sender.cancel()
            heartbeat.cancel()
        }
    }

    private suspend fun receive(socket: HomeServerSocket, run: UUID, tokens: SendChannel<TranscriptToken>, stopping: AtomicBoolean) {
        val ids = HashMap<Long, UUID>()
        val shown = HashMap<Long, String>()
        // A frame for a line that already got its final would otherwise
        // get a fresh id and show the same words again as a new line.
        var finished = HashSet<Long>()
        while (true) {
            val frame = try {
                socket.receive()
            } catch (error: Exception) {
                // Only its own: an old run whose connection closed late
                // unhooked the new run's, and names added after that never
                // reached the computer.
                synchronized(lock) {
                    if (liveSocketRun == run) {
                        liveSocket = null
                        liveSocketRun = null
                    }
                }
                withContext(NonCancellable) { socket.close() }
                val cancelled = stopping.get()
                // A line still showing a live guess never gets its final
                // pass now: the rest of that sentence is lost, so it is
                // marked as cut (as the cloud engine does when it gives up)
                // instead of settling as if it were finished. Stopping
                // captions cancels this call and leaves her lines as they are.
                if (!cancelled) {
                    for (number in shown.keys.sorted()) {
                        val id = ids[number] ?: continue
                        val words = shown[number]?.let { trimmingWhitespace(it) }
                        if (words.isNullOrEmpty()) continue
                        tokens.trySend(
                            TranscriptToken(
                                utteranceID = id,
                                text = CaptionStabilizer.markingCutOff(words),
                                isFinal = true,
                                timestamp = nowSeconds(),
                            ),
                        )
                    }
                }
                val state = synchronized(lock) {
                    val ended = endSent
                    val reason = when {
                        stalled -> "no reply for ${stallSeconds.toInt()} s of speech"
                        pongLost -> "no answer to a ping for ${pongSeconds.toInt()} s"
                        else -> null
                    }
                    if (reason != null || !(cancelled || ended)) verified = null
                    reason to ended
                }
                if (state.first != null) throw EngineUnavailability.homeServerUnreachable(state.first!!)
                if (cancelled) throw CancellationException("cancelled")
                if (state.second) return
                // Checked again for real next time: the pipeline asks
                // whether the computer is back.
                throw EngineUnavailability.homeServerUnreachable("connection lost: $error")
            }
            // Only a frame the phone understands proves the server is
            // working: garbage arriving often enough would otherwise keep
            // the stall check from ever handing captions to the phone.
            val message = HomeServerMessage.parse(frame) ?: continue
            noteReply()
            if (message !is HomeServerMessage.Text || message.utterance in finished) continue
            val number = message.utterance
            val id = ids[number] ?: UUID.randomUUID()
            ids[number] = id
            // The same checks the phone's own model gets: the names list
            // read back in a quiet moment, a TV sign-off, a "thanks" the
            // model barely heard. A server that sends no segments gets its
            // whole text checked as one, without Whisper's numbers.
            val echo = synchronized(lock) { echo }
            val text = filter.acceptedText(
                message.segments ?: listOf(WhisperSegmentSummary(message.text, 0f, 0f, 1f)),
                echo,
            )
            // A final pass that comes back empty (the model changed its
            // mind about a quiet tail) keeps what was already on screen.
            val words = if (text.isEmpty()) (shown[number] ?: "") else text
            if (message.isFinal) {
                ids.remove(number)
                shown.remove(number)
                finished.add(number)
                if (finished.size > 200) finished = finished.filterTo(HashSet()) { it > number - 100 }
            } else {
                shown[number] = words
            }
            if (words.isEmpty()) continue
            tokens.trySend(
                TranscriptToken(
                    utteranceID = id,
                    text = words,
                    isFinal = message.isFinal,
                    timestamp = nowSeconds(),
                    confidence = message.confidence,
                ),
            )
        }
    }

    private fun markEndSent() {
        synchronized(lock) { endSent = true }
    }

    /**
     * A server that stays connected but stops answering would leave the
     * captions frozen with nothing to say why. It always answers within
     * about 28 s of speech starting (its longest line), so speech sent
     * for `stallSeconds` with no reply at all means it is stuck: true
     * here closes the connection, and the phone's own model takes over.
     */
    private fun noteSent(chunk: FloatArray): Boolean = synchronized(lock) {
        samplesSent += chunk.size
        val speech = speechDetector.isSpeech(chunk)
        if (speechSinceReply == null && speech) {
            speechSinceReply = samplesSent
        }
        val start = speechSinceReply ?: return@synchronized false
        if (!((samplesSent - start).toDouble() >= stallSeconds * 16_000)) return@synchronized false
        stalled = true
        true
    }

    private fun noteReply() {
        synchronized(lock) {
            speechSinceReply = null
            lastHeardAt = timeSource.markNow()
        }
    }

    /**
     * A connection that died without closing (Wi-Fi dropped under a
     * router that never says so) sends no error, and the stall check
     * only notices after `stallSeconds` of speech. The server answers a
     * ping even while it is busy on a line, so one left unanswered for
     * `pongSeconds` means the path is gone: true here closes it, and the
     * phone's own model takes over. A ping is only sent once the last
     * one was answered.
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
        synchronized(lock) {
            pingSentAt = null
            lastHeardAt = timeSource.markNow()
        }
    }

    // MARK: - Connecting

    private fun destination(): Target {
        val url = HomeServer.url(address) ?: throw EngineUnavailability.homeServerUnreachable(HomeServer.NO_ADDRESS)
        val code = token()
        if (code.isNullOrEmpty()) throw EngineUnavailability.homeServerRejected(HomeServer.NO_CODE)
        return Target(url, code)
    }

    /**
     * Opens a connection, says hello and waits for the server's answer.
     * A server that never answers is closed after `handshakeSeconds`, so
     * a dead address doesn't leave captions waiting.
     */
    private suspend fun handshake(target: Target, languageCode: String, purpose: String, terms: List<String>): HomeServerSocket {
        val socket = try {
            connector.open(target.url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw EngineUnavailability.homeServerUnreachable("could not connect: $error")
        }
        try {
            // Sent inside the timed wait: a connection that never completes
            // (a computer asleep, a route that goes nowhere) holds the send
            // itself, for the system's minute rather than these seconds.
            val hello = HomeServer.hello(target.token, languageCode, terms, purpose, client, beam)
            val reply = firstReply(socket, handshakeSeconds, hello)
            return when (val message = HomeServerMessage.parse(reply)) {
                is HomeServerMessage.Ready -> socket
                is HomeServerMessage.Refused ->
                    if (message.code == "unauthorized") {
                        throw EngineUnavailability.homeServerRejected("pairing code refused ${message.detail}")
                    } else {
                        throw EngineUnavailability.homeServerUnreachable("server said ${message.code} ${message.detail}")
                    }
                else -> throw EngineUnavailability.homeServerUnreachable("unexpected reply")
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { socket.close() }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { socket.close() }
            throw (error as? EngineUnavailability) ?: EngineUnavailability.homeServerUnreachable("$error")
        }
    }

    private suspend fun firstReply(socket: HomeServerSocket, seconds: Double, after: String? = null): String = supervisorScope {
        val reply = async {
            if (after != null) socket.send(after)
            socket.receive()
        }
        val timer = async<String> {
            delay(seconds.seconds)
            // Closing is what makes a receive that ignores
            // cancellation give up.
            withContext(NonCancellable) { socket.close() }
            throw EngineUnavailability.homeServerUnreachable("no answer within $seconds s")
        }
        try {
            select {
                reply.onAwait { it }
                timer.onAwait { it }
            }
        } finally {
            reply.cancel()
            timer.cancel()
        }
    }

    companion object {
        const val DEFAULT_APPROVAL_SECONDS: Double = 30.0
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

private fun trimmingWhitespace(text: String): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    fun isSpace(scalar: Int) = Character.getType(scalar).toByte() == Character.SPACE_SEPARATOR || scalar == 0x09
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
