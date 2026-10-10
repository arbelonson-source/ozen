package com.arbelonson.ozen.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
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
import java.util.UUID

/**
 * Live captions from a model on the internet (see `CloudSpeech`), in the
 * same rhythm as the Whisper engine: a short silence ends a line, and a
 * line never runs past 28 seconds.
 *
 * While someone is still talking, the sentence so far goes out again
 * every couple of seconds so words appear as they are said; the request
 * after the pause is the one that stays. A live request that fails on a
 * weak connection is simply skipped. A final one gets a second try at
 * once, then the same audio (with whatever was said since) is sent again
 * after a growing pause, up to [FAILURES_BEFORE_STOPPING] rounds: up to
 * eight uploads of one sentence. What was already on screen is kept
 * rather than lost. A key problem, or that many failed rounds in a row,
 * end the stream so the screen can say why.
 *
 * The state a Swift actor would guard is behind one lock that is never
 * held across a suspension. [timeSource] measures the approval and the
 * duration of a live request; tests hand in the test scheduler's.
 */
class CloudSpeechEngine(
    val provider: CloudProvider = CloudProvider.OpenRouter,
    model: String? = null,
    private val http: CloudHTTP = UrlConnectionCloudHTTP(),
    private val filter: WhisperResultFilter = WhisperResultFilter(),
    private val failedSegmentPauseSeconds: Double = 1.0,
    private val approvalSeconds: Double = DEFAULT_APPROVAL_SECONDS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val apiKey: () -> String?,
) : TranscriptionEngine {
    override val kind: TranscriptionEngineKind = TranscriptionEngineKind.Cloud
    val model: String = model ?: provider.defaultModel

    private val lock = Any()
    private var vocabulary: List<String> = emptyList()
    private var echo: PromptEchoDetector? = null

    /**
     * The key the last check approved, so a restart doesn't ask again.
     * Trusted for [approvalSeconds] after the check or the last answered
     * request: the checks for whether the cloud can be reached again use
     * this same engine, and with the approval kept for good every one of
     * them after the first said yes without asking, so captions went back
     * to a cloud still out of reach and lost what was said until the phone
     * took over again.
     * Shorter than the minute between those checks
     * (`CaptionPipeline.cloudRecheckSeconds`), so each one really asks.
     */
    private var approvedKey: String? = null
    private var approvedAt: TimeMark? = null

    override suspend fun setVocabulary(terms: List<String>) {
        val detector = PromptEchoDetector(terms)
        synchronized(lock) {
            vocabulary = terms
            echo = if (detector.isEmpty) null else detector
        }
    }

    override suspend fun prepare(languageCode: String, progress: (EnginePreparationProgress) -> Unit): EngineAvailability {
        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.CheckingSupport))
        val key = currentKey() ?: return EngineAvailability.Unavailable(CloudSpeechError.KeyMissing.unavailability)
        if (isApproved(key)) return EngineAvailability.Available
        val response = try {
            http.send(provider.keyCheckRequest(key))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return EngineAvailability.Unavailable(CloudSpeechError.Offline.unavailability)
        }
        if (!provider.acceptsKeyCheck(response)) {
            return EngineAvailability.Unavailable(provider.failure(response).unavailability)
        }
        if (!provider.hasCreditLeft(response)) {
            return EngineAvailability.Unavailable(CloudSpeechError.OutOfCredit.unavailability)
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

    private fun isApproved(key: String): Boolean = synchronized(lock) {
        val at = approvedAt
        approvedKey == key && at != null && at.elapsedNow() < approvalSeconds.seconds
    }

    private fun forgetApproval() {
        synchronized(lock) { approvedKey = null }
    }

    private fun renewApproval(key: String) {
        synchronized(lock) { if (approvedKey == key) approvedAt = timeSource.markNow() }
    }

    private suspend fun run(languageCode: String, audio: Flow<FloatArray>, tokens: SendChannel<TranscriptToken>) = coroutineScope {
        val key = currentKey() ?: throw CloudSpeechError.KeyMissing
        val intake = SpeechIntake()
        val intakeJob = launch {
            try {
                audio.collect { chunk ->
                    ensureActive()
                    intake.append(chunk)
                }
            } finally {
                intake.markFinished()
            }
        }
        try {
            val rate = SAMPLE_RATE.toDouble()
            val pauseSamples = (PAUSE_SECONDS * rate).toInt()
            val padSamples = (TRAILING_PAD_SECONDS * rate).toInt()
            val keepSamples = (LEADING_KEEP_SECONDS * rate).toInt()
            val maxSamples = (MAX_UTTERANCE_SECONDS * rate).toInt()
            var utteranceID = UUID.randomUUID()
            var samplesAtLastPass = 0
            var lastShownText = ""
            var lastLivePassSeconds = 0.0
            var failuresInARow = 0

            while (true) {
                ensureActive()
                val status = intake.status()
                val total = status.count
                val speechEnd = status.lastSpeechEnd
                if (speechEnd == null) {
                    // Nothing said yet: no request at all, since silence is
                    // where models invent words, and silence costs money too.
                    if (total > keepSamples) {
                        intake.drop(total - keepSamples)
                    }
                    if (status.finished) break
                    delay(80)
                    continue
                }

                // Silence ahead of the first word is never sent. It piles up
                // while a request is out, and would be paid for and risk
                // invented words.
                val start = status.firstSpeechStart
                if (start != null && start > keepSamples) {
                    intake.drop(start - keepSamples)
                    continue
                }

                val pauseReached = total - speechEnd >= pauseSamples
                val tooLong = total >= maxSamples
                val isFinal = pauseReached || tooLong || status.finished
                // Never more often than a request takes, or they would pile up
                // behind each other on a slow connection. Not at all for a
                // service that bills each request as ten seconds or more
                // (`CloudProvider.livePasses`): a guess every two seconds would
                // cost several times the sentence itself.
                val liveSamples = (maxOf(LIVE_PASS_SECONDS, lastLivePassSeconds) * rate).toInt()
                if (!isFinal && (!provider.livePasses || total - samplesAtLastPass < liveSamples)) {
                    delay(50)
                    continue
                }

                val line = UtteranceCut.finishedLine(
                    total = total,
                    speechEnd = speechEnd,
                    pad = padSamples,
                    maxSamples = maxSamples,
                    stillTalkingAtCap = tooLong && !pauseReached && !status.finished,
                )
                val window: FloatArray = if (!isFinal) {
                    intake.copySamples(total)
                } else if (line.cut) {
                    val heard = intake.copySamples(line.end)
                    val cut = UtteranceCut.quietestPoint(
                        samples = heard,
                        before = heard.size,
                        lookBack = (LONG_CUT_LOOK_BACK_SECONDS * rate).toInt(),
                        frame = (LONG_CUT_FRAME_SECONDS * rate).toInt(),
                    )
                    heard.copyOfRange(0, cut)
                } else {
                    intake.copySamples(line.end)
                }
                val end = window.size
                samplesAtLastPass = total

                var turns: List<String>? = null
                var lastFailure: CloudSpeechError? = null
                val started = timeSource.markNow()
                for (attempt in 1..(if (isFinal) 2 else 1)) {
                    try {
                        turns = transcribe(window, key, languageCode)
                        lastFailure = null
                        renewApproval(key)
                        break
                    } catch (error: CloudSpeechError) {
                        if (error.needsPerson) {
                            forgetApproval()
                            throw error
                        }
                        lastFailure = error
                        if (attempt == 1 && isFinal) {
                            delay(400)
                        }
                    }
                }
                // One failed segment is one failure regardless of how many
                // attempts it took to give up on it -- a final segment's own
                // second try isn't a second, unrelated failure.
                if (lastFailure != null) {
                    failuresInARow += 1
                    if (failuresInARow >= FAILURES_BEFORE_STOPPING) {
                        // Giving up takes several seconds of retries, and by then
                        // the screen has settled the shown words as a finished
                        // line (the stabilizer's quiet-line safety net), which
                        // stopping leaves as written. Only the engine knows the
                        // rest of the sentence is lost, so it marks the line.
                        val shown = trimmingWhitespace(lastShownText)
                        if (shown.isNotEmpty()) {
                            tokens.trySend(
                                TranscriptToken(
                                    utteranceID = utteranceID,
                                    text = CaptionStabilizer.markingCutOff(shown),
                                    isFinal = true,
                                    timestamp = nowSeconds(),
                                ),
                            )
                        }
                        throw lastFailure
                    }
                } else {
                    failuresInARow = 0
                }
                if (!isFinal) {
                    lastLivePassSeconds = started.elapsedNow().toDouble(DurationUnit.SECONDS)
                }

                val timestamp = nowSeconds()
                if (!isFinal) {
                    val text = (turns ?: emptyList()).joinToString(" ")
                    if (text.isNotEmpty()) {
                        tokens.trySend(TranscriptToken(utteranceID = utteranceID, text = text, isFinal = false, timestamp = timestamp))
                        lastShownText = text
                    }
                } else {
                    // A final request that came back empty must not take away
                    // what was already on screen.
                    val finalTurns = turns?.takeIf { it.isNotEmpty() }
                        ?: (if (lastShownText.isEmpty()) emptyList() else listOf(lastShownText))
                    if (turns == null) {
                        // The request itself failed (as opposed to succeeding
                        // with nothing to say). Dropping the intake here would
                        // lose these words outright - or, after a live preview,
                        // commit the preview as the finished line and lose the
                        // rest of the sentence without a mark. Retrying with the
                        // same audio, bounded by the failuresInARow check above,
                        // is the only way not to; if that gives up, the line
                        // shown is cut with the "…" mark (see above).
                        delay((failedSegmentPauseSeconds * failuresInARow.toDouble() * 1_000).toLong())
                        continue
                    }
                    for ((index, turn) in finalTurns.withIndex()) {
                        tokens.trySend(
                            TranscriptToken(
                                utteranceID = if (index == 0) utteranceID else UUID.randomUUID(),
                                text = turn,
                                isFinal = true,
                                timestamp = timestamp,
                                startsNewSpeakerTurn = index > 0,
                            ),
                        )
                    }
                    intake.drop(end)
                    utteranceID = UUID.randomUUID()
                    samplesAtLastPass = 0
                    lastShownText = ""
                    lastLivePassSeconds = 0.0
                    if (status.finished && total - end == 0) break
                }
            }
        } finally {
            intakeJob.cancel()
        }
    }

    private suspend fun transcribe(window: FloatArray, key: String, languageCode: String): List<String> {
        val names = synchronized(lock) { vocabulary }
        val request = provider.transcriptionRequest(
            model = model,
            apiKey = key,
            // Measurement mode hands speech from across a room over at
            // -45 to -60 dBFS, where a 16-bit file keeps only a few bits
            // of it.
            wav = WAVFile.pcm16(SpeechGain.normalized(window), SAMPLE_RATE),
            languageCode = languageCode,
            vocabulary = names,
        )
            // A service that streams has no request per sentence; it gets
            // its own engine (see `CloudProvider.engine`).
            ?: throw CloudSpeechError.BadReply
        val response = try {
            http.send(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
            throw CloudSpeechError.Offline
        }
        val echo = synchronized(lock) { echo }
        return CloudSpeech.turns(provider.transcript(response), filter)
            .filter { !(echo?.isEcho(it) ?: false) }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * The phone's model and the home computer end a line after 0.7 s of
         * quiet (measured in `WhisperKitEngine`); this was left at 0.8 when
         * they moved, which ran separate turns together into one line.
         */
        const val PAUSE_SECONDS = 0.7
        const val LIVE_PASS_SECONDS = 2.0
        const val MAX_UTTERANCE_SECONDS = 28.0
        const val FAILURES_BEFORE_STOPPING = 4
        const val DEFAULT_APPROVAL_SECONDS: Double = 30.0
        private const val TRAILING_PAD_SECONDS = 0.3
        private const val LEADING_KEEP_SECONDS = 0.5
        private const val LONG_CUT_LOOK_BACK_SECONDS = 2.0
        private const val LONG_CUT_FRAME_SECONDS = 0.05
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
