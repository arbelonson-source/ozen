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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The phone's own voice, saying the person's replies through the phone's
 * speech engine. The engine runs as an app of its own (44 MB on the
 * emulator), held at Ozen's priority for as long as Ozen is connected to
 * it, and each question to it waits for that app: its list of voices took
 * a tenth of a second on the emulator, on every return to the app and
 * before every reply. So it is connected only while something needs it (a
 * look for its voices, a reply, a screen that speaks being open), let go
 * of [IDLE_MILLIS] after, and asked on a thread of its own, never the
 * screen's. Everything else here runs on the screen's thread.
 */
class PhoneVoice(context: Context) : SpeechSynthesizing {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // One question to the engine at a time, in the order asked; the thread
    // ends after a few idle seconds and comes back with the next one.
    private val engineThread = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { Thread(it, "PhoneVoice") }

    private class Connection {
        @Volatile var engine: TextToSpeech? = null
        @Volatile var answered = false
        var ready = false
    }

    // Held busy by [holding] from the moment it is asked for until the
    // engine has taken it or it is given up.
    private class Reply(val text: String, val rate: Float) {
        val holding: String = UUID.randomUUID().toString()
        var taken = false
    }

    private val pending = mutableSetOf<String>()
    @Volatile private var connection: Connection? = null
    @Volatile private var current: Reply? = null

    // A reply asked for while the engine was being connected, said once it answers.
    private var waiting: Reply? = null
    private var holds = 0
    @Volatile private var looking = 0
    private val letGoWhenIdle = Runnable { if (holds == 0 && looking == 0) rest() }

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

    /**
     * The languages the phone has a voice for, once its speech engine has
     * answered: the Say screen and Settings say when one is missing, as on
     * the iPhone, rather than only greying out the buttons. The voices found
     * last stay listed while the engine is let go of.
     */
    val installed = MutableStateFlow<Set<UILanguage>?>(null)

    @Volatile override var isSpeaking = false
        private set
    override val isBusy: Boolean get() = pending.isNotEmpty()
    override val hasHebrewVoice: Boolean get() = hasVoice(UILanguage.Hebrew)
    override var onSpeakingChanged: ((Boolean) -> Unit)? = null

    override fun hasVoice(language: UILanguage): Boolean = installed.value?.contains(language) == true

    /** Looks again for the phone's voices, connecting its speech engine if it was let go of. */
    override fun refreshVoice() {
        val made = connection ?: return connect()
        if (!made.answered) return
        looking++
        val ready = made.ready
        engineThread.execute {
            val engine = made.engine
            onLookup?.invoke()
            val found = if (ready && engine != null && engine.voices != null) languagesOn(engine) else null
            main.post { looked(made, found) }
        }
    }

    /** Called on the thread each look for the voices runs on; a seam for tests. */
    @Volatile internal var onLookup: (() -> Unit)? = null

    /** A look for the voices, or a connection whose answer brings one, not finished yet. */
    internal val isLooking: Boolean get() = looking > 0 || connection?.answered == false

    /**
     * Keeps the phone's speech engine connected while a screen that speaks
     * is open, so a reply is said at once; the call it returns lets go.
     */
    fun hold(): () -> Unit {
        holds++
        main.removeCallbacks(letGoWhenIdle)
        if (connection == null) connect()
        var held = true
        return {
            if (held) {
                held = false
                holds--
                idleLater()
            }
        }
    }

    /** The most the phone's speech engine takes in one go; tests make it small. */
    @Volatile internal var pieceLimit = TextToSpeech.getMaxSpeechInputLength()

    override fun speak(text: String, rate: Float) {
        pending.clear()
        waiting = null
        val reply = Reply(text, rate)
        current = reply
        pending += reply.holding
        val made = connection
        if (made != null && made.answered && made.ready) hand(made, reply, retry = true) else sayWhenConnected(reply, refused = null)
    }

    override fun stop() {
        val talking = pending.isNotEmpty()
        pending.clear()
        waiting = null
        current = null
        val made = connection
        if (made == null) {
            if (talking) quiet() else idleLater()
            return
        }
        // Quiet only once the engine has stopped saying it: captions come
        // back then, and must not hear the rest of the reply. A look for the
        // voices may be ahead of the stop on the engine's thread.
        val stopping = UUID.randomUUID().toString()
        if (talking) pending += stopping
        engineThread.execute {
            made.engine?.stop()
            main.post { stopped(stopping) }
        }
        if (!talking) idleLater()
    }

    // A reply asked for meanwhile owns the speaking from then on.
    private fun stopped(stopping: String) {
        if (pending.remove(stopping) && pending.isEmpty()) quiet()
    }

    /** Let go of, by [rest] or after nothing needed it for a while, until it is connected again. */
    internal val isResting: Boolean get() = connection == null

    /**
     * Lets go of the phone's speech engine and says nothing; out of sight,
     * whatever still holds it. Bound, the engine's own process is held at
     * the app's priority for as long as captions run in the background:
     * memory a small phone can't take back for the person's other apps.
     * [refreshVoice], [hold] or [speak] connects again.
     */
    fun rest() {
        val made = connection ?: return
        if (!made.answered || pending.isNotEmpty() || waiting != null) return
        main.removeCallbacks(letGoWhenIdle)
        letGoOf(made)
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
        current = null
        main.removeCallbacks(letGoWhenIdle)
        connection?.let(::letGoOf)
    }

    @Volatile internal var onReconnect: (() -> Unit)? = null

    private fun connect() {
        val made = Connection()
        connection = made
        onReconnect?.invoke()
        engineThread.execute {
            // The engine may answer before it is even made, on a thread of
            // its own: the answer waits behind the making on this thread.
            made.engine = TextToSpeech(appContext) { status ->
                engineThread.execute { main.post { answered(made, status == TextToSpeech.SUCCESS) } }
            }.apply { setOnUtteranceProgressListener(progress) }
        }
    }

    private fun reconnect() {
        connection?.let(::letGoOf)
        connect()
    }

    private fun letGoOf(made: Connection) {
        if (connection === made) connection = null
        engineThread.execute { made.engine?.shutdown() }
    }

    private fun answered(made: Connection, ok: Boolean) {
        if (made !== connection) return
        made.ready = ok
        if (ok) {
            looking++
            engineThread.execute {
                onLookup?.invoke()
                val found = made.engine?.let(::languagesOn).orEmpty()
                main.post { looked(made, found) }
            }
        } else {
            installed.value = emptySet()
        }
        made.answered = true
        waiting?.let { reply ->
            waiting = null
            if (ok) hand(made, reply, retry = false) else unsaid(reply)
        }
        idleLater()
    }

    // Null when the engine didn't answer the look: updated or stopped while
    // the app was away, it lets go of the app for good and never binds again
    // by itself, and one that failed to start stays failed. Either is
    // connected again rather than saying no voice is installed until the
    // app restarts.
    private fun looked(made: Connection, found: Set<UILanguage>?) {
        looking--
        if (made === connection) {
            if (found != null) {
                installed.value = found
            } else {
                // A reply it was saying as it went is never finished by it;
                // one still being handed over is said once it is connected.
                if (current?.taken != false && pending.isNotEmpty()) {
                    pending.clear()
                    quiet()
                }
                reconnect()
            }
        }
        idleLater()
    }

    private fun languagesOn(engine: TextToSpeech): Set<UILanguage> =
        UILanguage.entries.filterTo(HashSet()) { engine.isLanguageAvailable(Locale.forLanguageTag(it.speechVoiceCode)) >= TextToSpeech.LANG_AVAILABLE }

    private fun sayWhenConnected(reply: Reply, refused: Connection?) {
        val now = connection
        when {
            now == null || now === refused -> {
                waiting = reply
                reconnect()
            }
            !now.answered -> waiting = reply
            now.ready -> hand(now, reply, retry = false)
            else -> {
                waiting = reply
                reconnect()
            }
        }
    }

    private fun hand(made: Connection, reply: Reply, retry: Boolean) {
        val language = UILanguage.forSpeaking(reply.text, otherwise = Localization.language)
        if (language == null) {
            unsaid(reply)
            return
        }
        val pieces = SpeechChunks.of(reply.text, pieceLimit)
        val ids = pieces.map { UUID.randomUUID().toString() }
        pending += ids
        engineThread.execute {
            val engine = made.engine
            val said = if (reply === current && engine != null) say(engine, language, reply.rate, pieces, ids) else Said.Refused
            main.post { handed(made, reply, language, ids, said, retry) }
        }
    }

    // Not taken because the engine let go of the app, or taken by one let
    // go of meanwhile: said once it is connected again, held busy meanwhile.
    // One with no voice on the phone is not said at all, and the voices
    // listed lose that language, as a look would have found.
    private fun handed(made: Connection, reply: Reply, language: UILanguage, ids: List<String>, said: Said, retry: Boolean) {
        if (said == Said.NoVoice) installed.value = installed.value?.minus(language)
        if (reply !== current || reply.holding !in pending) return
        if (said == Said.Taken && made === connection) {
            reply.taken = true
            pending.remove(reply.holding)
            if (pending.isEmpty()) quiet()
            return
        }
        pending.removeAll(ids.toSet())
        if (retry && said != Said.NoVoice) sayWhenConnected(reply, refused = made) else unsaid(reply)
    }

    private enum class Said { Taken, Refused, NoVoice }

    private fun unsaid(reply: Reply) {
        pending.remove(reply.holding)
        if (pending.isEmpty()) quiet()
    }

    // On the engine's thread. Refused when the engine wouldn't take every
    // piece of the reply: what it had taken is dropped, so it is said again
    // whole or not at all. A voice removed since the voices were looked for
    // is found here, rather than the reply being said in the last
    // language's voice; an engine that let go of the app answers no voices.
    private fun say(engine: TextToSpeech, language: UILanguage, rate: Float, pieces: List<String>, ids: List<String>): Said {
        if (engine.setLanguage(Locale.forLanguageTag(language.speechVoiceCode)) < TextToSpeech.LANG_AVAILABLE) {
            if (engine.voices == null) return Said.Refused
            // Not said, and the reply it replaces is not said either: it
            // would go on over the captions that come back.
            engine.stop()
            return Said.NoVoice
        }
        engine.setSpeechRate(rate / NORMAL_RATE)
        SpeakerChoice.beforeSpeaking(appContext, engine)
        // The engine refuses a longer text outright, and nothing was said:
        // it goes in pieces, one after another, as one reply.
        val queued = SpeechChunks.queue(pieces, ids) { piece, id, flush ->
            engine.speak(piece, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS
        }
        if (!queued.complete && queued.ids.isNotEmpty()) engine.stop()
        return if (queued.complete) Said.Taken else Said.Refused
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
        idleLater()
    }

    private fun idleLater() {
        main.removeCallbacks(letGoWhenIdle)
        if (connection != null && holds == 0 && looking == 0 && pending.isEmpty() && waiting == null) main.postDelayed(letGoWhenIdle, IDLE_MILLIS)
    }

    internal companion object {
        const val NORMAL_RATE = 0.5f

        /** How long the engine stays connected once nothing needs it: a reply soon after is said at once. */
        const val IDLE_MILLIS = 20_000L
    }
}
