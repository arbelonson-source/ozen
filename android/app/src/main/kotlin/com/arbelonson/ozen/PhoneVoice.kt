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
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<String>()
    @Volatile private var ready = false
    @Volatile private var answered = false
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            answered = true
            refreshVoice()
        }
    }

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

    init {
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
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
            },
        )
    }

    override fun hasVoice(language: UILanguage): Boolean = installed.value?.contains(language) == true

    override fun refreshVoice() {
        if (!answered) return
        installed.value = if (!ready) {
            emptySet()
        } else {
            UILanguage.entries.filterTo(HashSet()) { engine.isLanguageAvailable(Locale.forLanguageTag(it.speechVoiceCode)) >= TextToSpeech.LANG_AVAILABLE }
        }
    }

    override fun speak(text: String, rate: Float) {
        val language = UILanguage.forSpeaking(text, otherwise = Localization.language)
        engine.setLanguage(Locale.forLanguageTag(language.speechVoiceCode))
        engine.setSpeechRate(rate / NORMAL_RATE)
        val id = UUID.randomUUID().toString()
        pending.clear()
        pending += id
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) main.post { ended(id) }
    }

    override fun stop() {
        val talking = pending.isNotEmpty()
        pending.clear()
        engine.stop()
        if (talking) quiet()
    }

    private fun ended(utteranceId: String) {
        pending.remove(utteranceId)
        quiet()
    }

    private fun quiet() {
        isSpeaking = false
        onSpeakingChanged?.invoke(false)
    }

    private companion object {
        const val NORMAL_RATE = 0.5f
    }
}
