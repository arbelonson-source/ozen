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
    @Volatile private var resting = false

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
        if (resting) {
            reconnect()
            return
        }
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
        if (answered && !resting && say(text, rate)) return
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
        if (!resting) engine.stop()
        if (talking) quiet()
    }

    /** Let go of by [rest] until it is connected again. */
    internal val isResting: Boolean get() = resting

    /**
     * Lets go of the phone's speech engine while the app is out of sight
     * and says nothing. Bound, the engine's own process (44 MB on the
     * emulator) is held at the app's priority for as long as captions run
     * in the background: memory a small phone can't take back for the
     * person's other apps. The voices found last stay listed, and
     * [refreshVoice] or [speak] connects again.
     */
    fun rest() {
        if (resting || !answered || pending.isNotEmpty() || waiting != null) return
        resting = true
        ready = false
        engine.shutdown()
    }

    /**
     * Lets go of the phone's speech engine for good. Android holds the
     * connection to it, and through its listener this voice, for a while
     * even after it is shut down: whoever this voice calls back is let go
     * of here, or a closed session stays in memory through it.
     */
    fun shutdown() {
        onSpeakingChanged = null
        onReconnect = null
        pending.clear()
        waiting = null
        if (!resting) engine.shutdown()
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

    @Volatile internal var onReconnect: (() -> Unit)? = null

    private fun reconnect() {
        if (!resting) engine.shutdown()
        resting = false
        onReconnect?.invoke()
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

    // False when the engine wouldn't take every piece of the reply: what it
    // had taken is dropped, so it is said again whole or not at all.
    private fun say(text: String, rate: Float): Boolean {
        val language = UILanguage.forSpeaking(text, otherwise = Localization.language) ?: return false
        engine.setLanguage(Locale.forLanguageTag(language.speechVoiceCode))
        engine.setSpeechRate(rate / NORMAL_RATE)
        SpeakerChoice.beforeSpeaking(appContext, engine)
        // The engine refuses a longer text outright, and nothing was said:
        // it goes in pieces, one after another, as one reply.
        val queued = SpeechChunks.queue(SpeechChunks.of(text, pieceLimit)) { piece, id, flush ->
            engine.speak(piece, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS
        }
        if (queued.complete) {
            pending += queued.ids
            return true
        }
        if (queued.ids.isNotEmpty()) engine.stop()
        return false
    }

    // A piece of a longer reply, or a reply replaced by the next one, ends
    // the speaking only once nothing more is waiting to be said.
    private fun ended(utteranceId: String) {
        pending.remove(utteranceId)
        if (pending.isEmpty()) quiet()
    }

    private fun quiet() {
        SpeakerChoice.afterSpeaking(appContext)
        isSpeaking = false
        onSpeakingChanged?.invoke(false)
    }

    private companion object {
        const val NORMAL_RATE = 0.5f
    }
}
