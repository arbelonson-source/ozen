package com.arbelonson.ozen

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.arbelonson.ozen.core.EngineAvailability
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.TranscriptToken
import com.arbelonson.ozen.core.TranscriptionEngine
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's own speech recognizer, on the phone only: what captions
 * while the chosen model waits for Wi-Fi, as Apple's recognizer does on
 * the iPhone (see `BuiltInWhileWaiting`). Only the on-device recognizer
 * is ever asked, never the one that sends audio to a server, and only
 * for a language installed on the phone.
 *
 * Ozen keeps the microphone: the recognizer hears Ozen's own audio
 * through a pipe, so sound alerts, voices and the recent audio go on as
 * with any engine, and two recorders never fight over the microphone. A
 * segmented session on that audio runs until the audio ends, each
 * segment one line, and a new session follows one that ends early.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class BuiltInSpeechEngine(private val context: Context, private val refusal: BuiltInRefusal = BuiltInRefusal()) : TranscriptionEngine {
    override val kind: TranscriptionEngineKind = TranscriptionEngineKind.AppleSpeech

    /** The recognizer's own name for the spoken language, from those installed on the phone; set by [prepare]. */
    private var languageTag: String? = null

    override suspend fun prepare(languageCode: String, progress: (EnginePreparationProgress) -> Unit): EngineAvailability {
        progress(EnginePreparationProgress(EnginePreparationProgress.Stage.CheckingSupport))
        refusal.reason?.let { why ->
            return EngineAvailability.unavailable(EngineUnavailability.Kind.LanguageNotSupportedOnDevice, "the recognizer refused Ozen's audio before: $why")
        }
        val installed = installedLanguages(context)
            ?: return EngineAvailability.unavailable(EngineUnavailability.Kind.LanguageNotSupportedOnDevice, "no recognition on the phone alone")
        val tag = installed.firstOrNull { BuiltInRecognition.matches(it, languageCode) }
            ?: return EngineAvailability.unavailable(EngineUnavailability.Kind.LanguageNotSupportedOnDevice, "$languageCode is not installed for recognition on the phone")
        languageTag = tag
        return EngineAvailability.Available
    }

    override fun stream(languageCode: String, audio: Flow<FloatArray>): Flow<TranscriptToken> = callbackFlow {
        val tag = languageTag ?: languageCode
        val writing = AtomicReference<OutputStream?>(null)
        val audioEnded = AtomicBoolean(false)
        var reading: ParcelFileDescriptor? = null
        var line = UUID.randomUUID()
        var session = 0
        var sessionEnded = 0
        var sessionStartedAt = 0L
        var heardInSession = false
        var quickEnds = 0
        var reconnects = 0
        lateinit var recognizer: SpeechRecognizer
        lateinit var listener: RecognitionListener

        // Any other ending means this phone's recognizer doesn't take
        // Ozen's audio as it should: it isn't asked again until the app
        // starts afresh, and the wait for Wi-Fi comes back as before.
        fun refused(why: String) {
            refusal.reason = why
            close(BuiltInRecognitionStopped(why))
        }

        fun connect() {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.setRecognitionListener(listener)
        }

        fun startSession() {
            val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
            reading?.let { runCatching { it.close() } }
            reading = readEnd
            writing.getAndSet(ParcelFileDescriptor.AutoCloseOutputStream(writeEnd))?.let { runCatching { it.close() } }
            session += 1
            sessionStartedAt = SystemClock.elapsedRealtime()
            heardInSession = false
            recognizer.startListening(intent(tag, readEnd))
        }

        // Once the audio stops, a session that heard only silence ends
        // with "no match": that is the end of the captions, not an error.
        // A session that ends while the audio goes on (a recognizer with
        // a time limit or one that stops at a long silence; the
        // emulator's went on through two minutes of it) is followed by a
        // new one on the audio that comes next. Only a recognizer that
        // gives up at once, again and again, ends the stream, and the
        // pipeline's retry takes it from there.
        fun ended(why: String) {
            if (sessionEnded == session) return
            sessionEnded = session
            if (audioEnded.get()) {
                close()
                return
            }
            val quick = !heardInSession && SystemClock.elapsedRealtime() - sessionStartedAt < QUICK_END_MILLIS
            quickEnds = if (quick) quickEnds + 1 else 0
            if (quickEnds >= MAX_QUICK_ENDS) {
                refused("the built-in recognition $why, $quickEnds times in a row at once")
                return
            }
            startSession()
        }

        // A session started right after another question to the
        // recognizer can reach it while it still shuts down from that
        // question: Android drops the session and answers "server
        // disconnected". That is a race, not a refusal of Ozen's audio, so
        // a fresh recognizer is asked after a moment, a few times at most.
        fun reconnect(error: Int) {
            if (sessionEnded == session) return
            sessionEnded = session
            if (audioEnded.get()) {
                close()
                return
            }
            reconnects += 1
            if (reconnects > MAX_RECONNECTS) {
                refused("the built-in recognition lost its connection (error $error), $reconnects times in a row")
                return
            }
            launch(Dispatchers.Main) {
                delay(RECONNECT_MILLIS)
                if (isClosedForSend) return@launch
                recognizer.destroy()
                connect()
                startSession()
            }
        }

        listener = object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) {
                val text = said(partialResults) ?: return
                heardInSession = true
                trySend(TranscriptToken(line, text, isFinal = false, timestamp = now()))
            }

            override fun onSegmentResults(segmentResults: Bundle) = finished(segmentResults)

            override fun onResults(results: Bundle) {
                finished(results)
                ended("ended its session")
            }

            override fun onEndOfSegmentedSession() = ended("ended its session")

            override fun onError(error: Int) {
                when (BuiltInRecognition.sessionError(error)) {
                    BuiltInRecognition.SessionError.HeardNothing -> ended("heard nothing (error $error)")
                    BuiltInRecognition.SessionError.Race -> reconnect(error)
                    BuiltInRecognition.SessionError.Refusal -> refused("the built-in recognition stopped with error $error")
                }
            }

            private fun finished(results: Bundle) {
                val text = said(results) ?: return
                heardInSession = true
                trySend(TranscriptToken(line, text, isFinal = true, timestamp = now(), confidence = confidence(results)))
                line = UUID.randomUUID()
            }

            override fun onReadyForSpeech(params: Bundle?) {
                reconnects = 0
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        withContext(Dispatchers.Main) {
            connect()
            startSession()
        }
        val writer = launch(Dispatchers.IO) {
            audio.collect { chunk ->
                val bytes = pcm16(chunk)
                // A session that ended stops reading; the next one takes
                // the audio that comes after.
                try {
                    writing.get()?.write(bytes)
                } catch (_: IOException) {
                }
            }
            withContext(Dispatchers.Main) {
                audioEnded.set(true)
                writing.getAndSet(null)?.let { runCatching { it.close() } }
            }
        }
        awaitClose {
            writer.cancel()
            context.mainExecutor.execute {
                recognizer.cancel()
                recognizer.destroy()
                writing.getAndSet(null)?.let { runCatching { it.close() } }
                reading?.let { runCatching { it.close() } }
            }
        }
    }

    private fun intent(tag: String, audio: ParcelFileDescriptor) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
    }

    private class BuiltInRecognitionStopped(message: String) : Exception(message)

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val QUICK_END_MILLIS = 1_000L
        private const val MAX_QUICK_ENDS = 5
        private const val SUPPORT_ASKS = 3
        private const val MAX_RECONNECTS = 3
        private const val RECONNECT_MILLIS = 300L
        private const val SUPPORT_ANSWER_MILLIS = 2_000L
        private const val SUPPORT_RETRY_MILLIS = 300L

        /**
         * The languages the phone's recognizer has installed for use offline,
         * as it names them; null when it has no recognition on the phone alone.
         */
        suspend fun installedLanguages(context: Context): List<String>? = withContext(Dispatchers.Main) {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext null
            recognitionSupport(context).support?.installedOnDeviceLanguages
        }

        /**
         * What the phone's recognizer says about recognition on the phone
         * alone, or the error it gave instead (no error: no answer at all).
         * Asked right after another question, the recognizer can still be
         * shutting down from it: Android then drops the question without an
         * answer ("Service is unbinding") or answers "server disconnected".
         * Either way it is asked again after a moment, a few times at most,
         * so "Captions now" isn't refused, or never offered, over a race.
         */
        suspend fun recognitionSupport(context: Context): SupportAnswer = withContext(Dispatchers.Main) {
            var answer = SupportAnswer(null)
            for (asked in 1..SUPPORT_ASKS) {
                answer = askSupport(context)
                if (!answer.lost) break
                if (asked < SUPPORT_ASKS) delay(SUPPORT_RETRY_MILLIS)
            }
            answer
        }

        class SupportAnswer(val support: RecognitionSupport?, val error: Int? = null) {
            val lost: Boolean
                get() = support == null && (error == null || BuiltInRecognition.sessionError(error) == BuiltInRecognition.SessionError.Race)
        }

        private suspend fun askSupport(context: Context): SupportAnswer = withTimeoutOrNull(SUPPORT_ANSWER_MILLIS) {
            suspendCancellableCoroutine { waiting ->
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val destroyed = AtomicBoolean(false)
                fun destroy() {
                    if (!destroyed.getAndSet(true)) recognizer.destroy()
                }
                fun answered(answer: SupportAnswer) {
                    destroy()
                    if (waiting.isActive) waiting.resume(answer)
                }
                waiting.invokeOnCancellation { context.mainExecutor.execute(::destroy) }
                val callback = object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) = answered(SupportAnswer(recognitionSupport))

                    override fun onError(error: Int) = answered(SupportAnswer(null, error))
                }
                recognizer.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), context.mainExecutor, callback)
            }
        } ?: SupportAnswer(null)

        /** Ozen's audio, -1...1, as the 16-bit little-endian samples the recognizer reads. */
        fun pcm16(samples: FloatArray): ByteArray {
            val bytes = ByteArray(samples.size * 2)
            for ((index, sample) in samples.withIndex()) {
                val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                bytes[index * 2] = value.toByte()
                bytes[index * 2 + 1] = (value shr 8).toByte()
            }
            return bytes
        }

        /** What was said, or null for nothing heard. */
        fun said(results: Bundle): String? =
            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * The recognizer's confidence when it gave one. Android often gives
         * none (0 or -1): then nothing is marked uncertain, rather than
         * every word.
         */
        fun confidence(results: Bundle): Float? =
            results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it > 0f && it <= 1f }

        private fun now(): Double = System.currentTimeMillis() / 1_000.0
    }
}

/** Why this phone's recognizer last refused Ozen's audio, kept while the app runs; shared by every engine built for it. */
class BuiltInRefusal {
    @Volatile var reason: String? = null
}
