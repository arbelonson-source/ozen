package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Whisper on the phone, for Hebrew-primary live transcription (the Android
 * counterpart of the iPhone's `WhisperKitEngine`). The model itself sits
 * behind [WhisperPasses]; everything that decides when to run it, on what
 * audio and what to show is here.
 *
 * Shape of the streaming loop, and why: Whisper isn't a streaming model --
 * every pass re-decodes a whole (padded-to-30 s) window. So "live" here
 * means re-running the model on the current utterance's audio every
 * ~0.6 s of new speech and showing the latest hypothesis, then running
 * one last, more careful pass when a pause ends the utterance. Audio
 * intake and inference are separate loops on purpose: intake just
 * appends to a buffer and can never fall behind, while inference always
 * works on the *latest* audio -- if a pass takes longer than 0.6 s the
 * next one simply covers more audio, instead of a queue of stale passes
 * building up and the captions drifting further and further behind.
 *
 * [voiceScorerFactory] makes a new voice scorer for each stream (null when
 * the voice model isn't there: then every line goes to Whisper, as before;
 * see `VoiceEvidence`). [timeSource] measures the passes; tests hand in the
 * test scheduler's. [passDispatcher] is where the model runs.
 */
class OnDeviceWhisperEngine(
    private val modelName: String,
    private val loader: WhisperModelLoader,
    private val voiceScorerFactory: () -> ((FloatArray) -> Float?)? = { null },
    private val heat: () -> DeviceHeat = { DeviceHeat.Nominal },
    private val lowPowerMode: () -> Boolean = { false },
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val wallClockSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val passDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val filter: WhisperResultFilter = WhisperResultFilter(),
) : TranscriptionEngine {
    override val kind: TranscriptionEngineKind = TranscriptionEngineKind.WhisperKit

    class NotPrepared : IllegalStateException("the on-device model is not prepared")

    private val lock = Any()
    private var passes: WhisperPasses? = null
    private var model: WhisperLoadedModel? = null
    private var vocabulary: List<String> = emptyList()
    private var cellularDownloadAllowed = true

    /**
     * Token ids for the current vocabulary prompt, recomputed only when
     * the list changes (encoding is cheap but runs every pass otherwise).
     */
    private var promptCache: Pair<List<String>, List<Int>>? = null

    /** Rebuilt with the vocabulary; catches the prompt coming back as a caption on a quiet window. */
    private var echoDetector: PromptEchoDetector? = null

    /** How the passes have gone since the engine was made, for the journal. */
    private val tally = PassTally()

    /**
     * WhisperKit keeps decoder state on the instance, and so does a native
     * model, so two passes must never run on it at once. The pipeline cancels
     * a stream on pause, restart or recovery without waiting for it to end,
     * and a cancelled stream can still be inside a pass (a pass doesn't stop
     * part way). The next stream waits for this, at most one pass.
     */
    private val streamLock = Mutex()

    // How often the live preview re-runs is decided per pass by
    // `InferenceCadence` (0.6 s on a cool phone, slower when hot, in Low
    // Power Mode, or when the last pass was itself slow).

    /**
     * A gap this long with no speech ends the current utterance.
     *
     * It was 1.0 s. People answering each other leave shorter gaps than
     * that, so a whole exchange ran into one line: 764 turns of Hebrew
     * broadcast conversation became 139 lines, several voices in each,
     * and 10.0% of the words came out wrong against 8.4% with every turn
     * on its own. At 0.7 s it was 365 lines and 8.9%, and 51.6 min of
     * lectures stayed where they were (13.6% -> 13.3%, 326 lines for 368
     * sentences). Shorter still started cutting sentences in half: at
     * 0.5 s the lectures got worse (14.2%, 494 lines).
     */
    private val pauseSeconds = 0.7

    /** Audio kept after the last detected speech when finalizing, so a trailing soft consonant isn't clipped. */
    private val trailingPadSeconds = 0.3

    /** Audio kept while waiting in silence, so the first syllable of the next sentence is already in the buffer when speech is detected. */
    private val leadingKeepSeconds = 0.5

    /**
     * Whisper's window is 30 s; finalize before that so the model never
     * sees a truncated utterance. Sooner when the line's words would not
     * fit after the names (`WhisperKitDecodeRoom`).
     */
    private val maxUtteranceSeconds = 28.0

    /**
     * How far back from the end a line that ran too long looks for a
     * quiet moment to be cut at (`UtteranceCut`), and the stretch it
     * measures at a time.
     */
    private val longCutLookBackSeconds = 2.0
    private val longCutFrameSeconds = 0.05
    private val sampleRate = 16_000.0

    // MARK: - TranscriptionEngine

    override suspend fun setCellularDownloadAllowed(allowed: Boolean) {
        synchronized(lock) { cellularDownloadAllowed = allowed }
    }

    override suspend fun cancelDownload() {
        loader.cancelDownload()
    }

    override suspend fun setVocabulary(terms: List<String>) {
        val detector = PromptEchoDetector(terms)
        synchronized(lock) {
            vocabulary = terms
            echoDetector = if (detector.isEmpty) null else detector
        }
    }

    override suspend fun pendingDownloadMegabytes(): Int? {
        if (synchronized(lock) { passes } != null) return null
        return loader.pendingDownloadMegabytes()
    }

    override suspend fun pendingInstallMegabytes(): Int? {
        if (synchronized(lock) { passes } != null) return null
        return loader.pendingInstallMegabytes()
    }

    override suspend fun diagnosticsSummary(): String? = synchronized(lock) { "$modelName: ${tally.summary}" }

    override suspend fun prepare(languageCode: String, progress: (EnginePreparationProgress) -> Unit): EngineAvailability {
        if (synchronized(lock) { passes } != null) return EngineAvailability.Available
        val allowCellular = synchronized(lock) { cellularDownloadAllowed }
        val loaded = try {
            loader.load(languageCode, allowCellular, progress)
        } catch (error: CancellationException) {
            throw error
        } catch (why: EngineUnavailability) {
            return EngineAvailability.Unavailable(why)
        } catch (error: Exception) {
            return EngineAvailability.unavailable(EngineUnavailability.Kind.ModelLoadFailed, "$modelName: $error")
        }
        // One throwaway pass over a second of silence: the first run of a
        // model pays its set-up cost here rather than on the first real
        // sentence somebody says.
        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.WarmingUp, detail = modelName, isFirstTime = loaded.isFirstTime))
        try {
            runPass(loaded.passes, FloatArray(sampleRate.toInt()), WhisperPassOptions.live(languageCode))
        } catch (error: CancellationException) {
            // Stopped while warming up: nothing holds this model yet, so
            // nothing else would ever give it back.
            loaded.release()
            throw error
        } catch (error: Exception) {
            // A failed warm-up is not a broken model.
        }
        val superseded = synchronized(lock) {
            if (passes != null) {
                loaded
            } else {
                passes = loaded.passes
                model = loaded
                null
            }
        }
        // Another prepare stored its model while this one loaded: that one
        // stays and this copy goes back rather than sit in memory unused.
        superseded?.release()
        return EngineAvailability.Available
    }

    override fun release() {
        val held = synchronized(lock) {
            val current = model
            model = null
            passes = null
            promptCache = null
            current
        }
        held?.release()
    }

    override fun stream(languageCode: String, audio: Flow<FloatArray>): Flow<TranscriptToken> =
        tokenFlow { tokens -> streamLock.withLock { runStreaming(languageCode, audio, tokens) } }

    // MARK: - Streaming

    // A pass is native code no coroutine can interrupt. Stopping captions
    // asks it to stop, as the iPhone's cancelled WhisperKit pass does, then
    // still waits for it to return, so nothing outlives the stream; running
    // it out instead held the next start (and, on a closed session, the
    // model's memory) for a whole pass, about 30 s on the emulator.
    private suspend fun runPass(passes: WhisperPasses, audio: FloatArray, options: WhisperPassOptions): List<WhisperSegment> {
        val pass = CoroutineScope(passDispatcher).async { passes.run(audio, options) }
        try {
            return pass.await()
        } catch (stopped: CancellationException) {
            passes.abortRunningPass()
            withContext(NonCancellable) { pass.join() }
            throw stopped
        }
    }

    private suspend fun runStreaming(languageCode: String, audio: Flow<FloatArray>, tokens: SendChannel<TranscriptToken>) {
        val pipe = synchronized(lock) { passes } ?: throw NotPrepared()
        val specialTokenBegin = pipe.specialTokenBegin

        val intake = AudioIntake(voiceScorerFactory())
        coroutineScope {
            val intakeJob = launch {
                try {
                    audio.collect { chunk -> intake.append(chunk) }
                } finally {
                    intake.markFinished()
                }
            }
            try {
                streamLoop(pipe, specialTokenBegin, languageCode, intake, tokens)
            } finally {
                intakeJob.cancel()
            }
        }
    }

    private suspend fun streamLoop(
        pipe: WhisperPasses,
        specialTokenBegin: Int,
        languageCode: String,
        intake: AudioIntake,
        tokens: SendChannel<TranscriptToken>,
    ) {
        val livePass = WhisperPassOptions.live(languageCode)
        val finalPass = WhisperPassOptions.final(languageCode)
        val pauseSamples = (pauseSeconds * sampleRate).toInt()
        val padSamples = (trailingPadSeconds * sampleRate).toInt()
        val keepSamples = (leadingKeepSeconds * sampleRate).toInt()
        val longCutLookBack = (longCutLookBackSeconds * sampleRate).toInt()
        val longCutFrame = (longCutFrameSeconds * sampleRate).toInt()
        var lastLivePassSeconds: Double? = null

        var utteranceID = UUID.randomUUID()
        var samplesAtLastPass = 0
        var lastShownText = ""
        var lastShownConfidence: Float? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            // The counts only: most turns just wait for more audio, and
            // copying up to 28 seconds of it twenty times a second to find
            // that out cost battery all through a conversation.
            val status = intake.status()
            val total = status.count

            val speechEnd = status.lastSpeechEnd
            if (speechEnd == null) {
                // Nothing but silence so far: don't run the model at all
                // (that's where hallucinations come from), just keep a
                // little lead-in audio and wait.
                if (total > keepSamples) {
                    intake.drop(total - keepSamples)
                }
                if (status.finished) break
                delay(80)
                continue
            }

            // The longer the names list, the less room is left for the
            // line's own words (`WhisperKitDecodeRoom`).
            val prompt = promptTokens(pipe)
            val maxSamples = (WhisperKitDecodeRoom.longestLineSeconds(prompt?.size ?: 0, maxUtteranceSeconds) * sampleRate).toInt()
            val pauseReached = total - speechEnd >= pauseSamples
            val tooLong = total >= maxSamples
            val isFinal = pauseReached || tooLong || status.finished
            val interval = InferenceCadence.secondsBetweenLivePasses(
                heat = heat(),
                lowPowerMode = lowPowerMode(),
                lastPassSeconds = lastLivePassSeconds,
            )
            val enoughNewAudio = total - samplesAtLastPass >= (interval * sampleRate).toInt()
            if (!isFinal && !enoughNewAudio) {
                delay(50)
                continue
            }

            // Only this loop drops audio from the front, so the first
            // `total` samples are still the ones the counts described.
            val window: FloatArray
            val line = UtteranceCut.finishedLine(
                total = total,
                speechEnd = speechEnd,
                pad = padSamples,
                maxSamples = maxSamples,
                stillTalkingAtCap = tooLong && !pauseReached && !status.finished,
            )
            if (!isFinal) {
                window = intake.copySamples(total)
            } else if (line.cut) {
                // Still talking at the cap, or more than the longest line
                // waiting after a slow pass: end the line in the quietest
                // moment of the last two seconds before the cap rather than
                // mid-word. What comes after it is kept and starts the next line.
                val heard = intake.copySamples(line.end)
                val cut = UtteranceCut.quietestPoint(
                    samples = heard,
                    before = heard.size,
                    lookBack = longCutLookBack,
                    frame = longCutFrame,
                )
                window = heard.copyOfRange(0, cut)
            } else {
                window = intake.copySamples(line.end)
            }
            val end = window.size
            samplesAtLastPass = total

            // Kitchen clatter and a running tap pass the energy detector
            // and come back from Whisper as confident Hebrew. A stretch in
            // which the voice model heard no voice at all never reaches
            // Whisper; a line already on screen is always finished. So is
            // the last one when captions stop: its final quarter-second
            // may not have been scored yet.
            if (!status.finished && intake.hasVoice(end) == false && lastShownText.isEmpty()) {
                synchronized(lock) { tally.recordSkippedWithoutVoice() }
                if (isFinal) {
                    intake.drop(end)
                    utteranceID = UUID.randomUUID()
                    samplesAtLastPass = 0
                    lastLivePassSeconds = null
                } else if (total > keepSamples) {
                    // Clatter that keeps the energy detector busy would
                    // otherwise pile up for 28 s, and the first words after
                    // it would wait on a pass over all of it. The half
                    // second kept is longer than a chunk, so a word just
                    // starting (not yet scored) stays.
                    intake.drop(total - keepSamples)
                    samplesAtLastPass = keepSamples
                }
                continue
            }

            var options = if (isFinal) finalPass else livePass
            options = options.copy(promptTokens = prompt)
            val windowSeconds = window.size.toDouble() / sampleRate
            if (!isFinal) {
                options = options.copy(maxTokens = WhisperKitDecodeRoom.livePassTokens(windowSeconds))
            }
            // A voice across the room reaches the model quiet; brought up to
            // a common level it made fewer mistakes (speaker across the room
            // 51.3 -> 49.6% of words wrong, 8 dB quieter 86.5 -> 84.9%) and
            // changed nothing up close (see `SpeechGain`).
            val heard = SpeechGain.normalized(window)
            val passStarted = timeSource.markNow()
            val results: List<WhisperSegment> = try {
                runPass(pipe, heard, options)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // One pass failing (the model busy, memory tight for a
                // moment) used to end the stream: the pipeline restarted the
                // engine and the sentence being spoken was lost with the
                // buffer. The same window gets one more try first.
                delay(250)
                runPass(pipe, heard, options)
            }
            if (!isFinal) {
                // Only live passes: a final pass may retry at higher
                // temperatures and would overstate how slow the phone is.
                val seconds = passStarted.elapsedNow().toDouble(DurationUnit.SECONDS)
                lastLivePassSeconds = seconds
                synchronized(lock) { tally.recordLivePass(seconds) }
            }
            val summaries = results.map {
                WhisperSegmentSummary(
                    text = it.text,
                    noSpeechProb = it.noSpeechProbability,
                    avgLogprob = WhisperPassScoring.averageLogprob(it, specialTokenBegin) ?: Float.NEGATIVE_INFINITY,
                    compressionRatio = WhisperPassScoring.compressionRatio(it, specialTokenBegin),
                    // Only on the pass that stays: a line still being written
                    // changes its mind about words as well as about itself.
                    uncertainWords = if (isFinal) WhisperPassScoring.uncertainWords(it, specialTokenBegin, languageCode) else emptyList(),
                    temperature = it.temperature ?: 0f,
                )
            }
            // A live pass that used all its room was looping on one sound
            // (see `WhisperKitDecodeRoom.livePassTokens`).
            val ranOut = !isFinal && WhisperKitDecodeRoom.livePassRanOut(
                wordTokens = results.sumOf { segment -> segment.tokens.count { it.id < specialTokenBegin } },
                seconds = windowSeconds,
            )
            val echo = synchronized(lock) { echoDetector }
            // A thank-you alone in a moment of voice is most likely
            // household noise (see `WhisperResultFilter.isUnvoicedPhrase`).
            // When captions stop, the last chunk may not be scored yet.
            val dropped = ranOut || filter.isUnvoicedPhrase(
                filter.acceptedText(summaries, echo),
                if (status.finished) null else intake.voicedChunks(end),
            )
            // Confidence has to describe exactly the text being shown, not
            // the whole pass -- a rejected hallucination segment can have a
            // confident logprob of its own and skew the mean either way for
            // content that never reaches the screen.
            val acceptedSegments = if (dropped) emptyList() else filter.accepted(summaries, echo)
            val text = if (dropped) "" else filter.acceptedText(summaries, echo)
            val confidence = CaptionConfidence.whisperConfidence(acceptedSegments)
            synchronized(lock) {
                tally.recordSegments(seen = summaries.size, accepted = acceptedSegments.size)
                if (isFinal) tally.recordFinalPass(cameBackEmpty = text.isEmpty())
            }

            // A final pass that comes back empty (the pad was silence and
            // the model changed its mind) must not erase what was shown --
            // and its confidence describes that unrelated, rejected pass,
            // not the text now being shown again, so it falls back too.
            val shown = if (text.isEmpty()) lastShownText else text
            val shownConfidence = if (text.isEmpty()) lastShownConfidence else confidence
            if (shown.isNotEmpty()) {
                tokens.trySend(
                    TranscriptToken(
                        utteranceID = utteranceID,
                        text = shown,
                        isFinal = isFinal,
                        timestamp = wallClockSeconds(),
                        confidence = shownConfidence,
                        uncertainWords = if (text.isEmpty()) emptyList() else acceptedSegments.flatMap { it.uncertainWords },
                    ),
                )
                lastShownText = shown
                lastShownConfidence = shownConfidence
            }

            if (isFinal) {
                intake.drop(end)
                utteranceID = UUID.randomUUID()
                samplesAtLastPass = 0
                lastShownText = ""
                lastShownConfidence = null
                // How long a pass over the finished line took says little
                // about the next, shorter one; its first preview shouldn't
                // wait on it.
                lastLivePassSeconds = null
                if (status.finished && total - end == 0) break
            }
        }
    }

    // MARK: - Vocabulary prompt

    /**
     * Encodes the names list the way WhisperKit's own CLI does for
     * `--prompt`: a leading space, special tokens stripped, and only the
     * whole names from the top of the list (ordered most-important-first)
     * that fit the budget.
     */
    private fun promptTokens(pipe: WhisperPasses): List<Int>? {
        val terms = synchronized(lock) { vocabulary }
        if (terms.isEmpty()) return null
        synchronized(lock) { promptCache }?.let { cached ->
            if (cached.first == terms) return cached.second
        }
        val begin = pipe.specialTokenBegin
        val encode = { text: String -> pipe.tokenize(text).filter { it < begin } }
        val text = VocabularyHints.whisperPrompt(terms, WhisperKitDecodeRoom.maxPromptTokens) { encode(it).size }
        if (text.isEmpty()) return null
        val ids = encode(" $text").take(WhisperKitDecodeRoom.maxPromptTokens)
        synchronized(lock) { promptCache = terms to ids }
        return ids.ifEmpty { null }
    }
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
