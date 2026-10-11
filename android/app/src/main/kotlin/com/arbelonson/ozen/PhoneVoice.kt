package com.arbelonson.ozen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SpeechSynthesizing
import com.arbelonson.ozen.core.UILanguage
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

class PhoneVoice(context: Context) : SpeechSynthesizing {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<String>()
    @Volatile private var ready = false
    @Volatile private var answered = false

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            main.post {
                if (utteranceId in pending) {
                    isSpeaking = true
                    onSpeakingChanged?.invoke(true)
                }
            }
        }

        override fun onDone(utteranceId: String) {
            main.post { ended(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            main.post { ended(utteranceId) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            main.post { ended(utteranceId) }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            main.post { ended(utteranceId) }
        }
    }

    private var engine: TextToSpeech = connect()

    // A reply asked for while the engine was being connected, said once it answers.
    private var waiting: (() -> Unit)? = null

    /**
     * The languages the phone has a voice for, once its speech engine has
     * answered: the Say screen and Settings say when one is missing, as on
     * the iPhone, rather than only greying out the buttons.
     */
    val installed = MutableStateFlow<Set<UILanguage>?>(null)

    @Volatile override var isSpeaking = false
        private set
    override val isBusy: Boolean get() = pending.isNotEmpty()
    override val hasHebrewVoice: Boolean get() = hasVoice(UILanguage.Hebrew)
    override var onSpeakingChanged: ((Boolean) -> Unit)? = null

    override fun hasVoice(language: UILanguage): Boolean = installed.value?.contains(language) == true

    override fun refreshVoice() {
        if (!answered) return
        // The phone's speech engine, updated or stopped while the app was
        // away, lets go of it for good and never binds again by itself, and
        // one that failed to start stays failed: either is connected again
        // rather than saying no voice is installed until the app restarts.
        if (ready && engine.voices != null) {
            listVoices()
            return
        }
        // A reply it was saying as it went is never finished by it.
        if (pending.isNotEmpty()) {
            pending.clear()
            quiet()
        }
        reconnect()
    }

    /** The most the phone's speech engine takes in one go; tests make it small. */
    @Volatile internal var pieceLimit = TextToSpeech.getMaxSpeechInputLength()

    override fun speak(text: String, rate: Float) {
        pending.clear()
        waiting = null
        if (answered && say(text, rate)) return
        // Not taken because the engine let go of the app, or before it has
        // answered: said once it is connected, the reply held busy meanwhile.
        val holding = UUID.randomUUID().toString()
        pending += holding
        waiting = {
            pending.remove(holding)
            if (!ready || !say(text, rate)) quiet()
        }
        if (answered) reconnect()
    }

    override fun stop() {
        val talking = pending.isNotEmpty()
        pending.clear()
        waiting = null
        engine.stop()
        if (talking) quiet()
    }

    /**
     * Lets go of the phone's speech engine for good. Android holds the
     * connection to it, and through its listener this voice and whoever
     * listens to it, until it is shut down.
     */
    fun shutdown() {
        onSpeakingChanged = null
        pending.clear()
        waiting = null
        engine.shutdown()
    }

    private fun connect(): TextToSpeech {
        lateinit var made: TextToSpeech
        made = TextToSpeech(appContext) { status ->
            main.post {
                if (made !== engine) return@post
                ready = status == TextToSpeech.SUCCESS
                answered = true
                listVoices()
                waiting?.let { say ->
                    waiting = null
                    say()
                }
            }
        }
        made.setOnUtteranceProgressListener(progress)
        return made
    }

    private fun reconnect() {
        engine.shutdown()
        ready = false
        answered = false
        engine = connect()
    }

    private fun listVoices() {
        installed.value = if (!ready) {
            emptySet()
        } else {
            UILanguage.entries.filterTo(HashSet()) { engine.isLanguageAvailable(Locale.forLanguageTag(it.speechVoiceCode)) >= TextToSpeech.LANG_AVAILABLE }
        }
    }

    // False when the engine wouldn't take the reply's first piece.
    private fun say(text: String, rate: Float): Boolean {
        val language = UILanguage.forSpeaking(text, otherwise = Localization.language)
        engine.setLanguage(Locale.forLanguageTag(language.speechVoiceCode))
        engine.setSpeechRate(rate / NORMAL_RATE)
        // The engine refuses a longer text outright, and nothing was said:
        // it goes in pieces, one after another, as one reply.
        SpeechChunks.of(text, pieceLimit).forEachIndexed { at, piece ->
            val id = UUID.randomUUID().toString()
            val queue = if (at == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(piece, queue, null, id) == TextToSpeech.SUCCESS) {
                pending += id
            } else if (at == 0) {
                return false
            }
        }
        return true
    }

    // A piece of a longer reply, or a reply replaced by the next one, ends
    // the speaking only once nothing more is waiting to be said.
    private fun ended(utteranceId: String) {
        pending.remove(utteranceId)
        if (pending.isEmpty()) quiet()
    }

    private fun quiet() {
        isSpeaking = false
        onSpeakingChanged?.invoke(false)
    }

    private companion object {
        const val NORMAL_RATE = 0.5f
    }
}
