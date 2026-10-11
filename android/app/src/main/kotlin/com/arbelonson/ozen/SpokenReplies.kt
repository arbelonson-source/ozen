package com.arbelonson.ozen

import com.arbelonson.ozen.core.SpeechPauseCoordinator
import com.arbelonson.ozen.core.SpeechSynthesizing
import com.arbelonson.ozen.core.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SpokenReplies(private val voice: SpeechSynthesizing, private val scope: CoroutineScope, private val captions: Captions) {
    interface Captions {
        val isListening: Boolean
        val isPaused: Boolean
        val duringCall: Boolean

        fun pause()

        fun resume()
    }

    private val hold = SpeechPauseCoordinator()

    var onSpeaking: ((Boolean) -> Unit)? = null

    init {
        voice.onSpeakingChanged = ::speakingChanged
    }

    val isSpeaking: Boolean get() = voice.isSpeaking

    val holdsCaptions: Boolean get() = hold.isHoldingCaptions

    fun canSay(text: String): Boolean = voice.canSay(text)

    fun speak(text: String, rate: Float): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !voice.canSay(trimmed)) return false
        if (hold.willSpeak(captions.isListening)) captions.pause()
        voice.speak(trimmed, rate)
        return true
    }

    fun askToRepeat(rate: Float): Boolean = speak(REPEAT_REQUEST, rate)

    fun stop() = voice.stop()

    fun userTookControl() = hold.userTookControl()

    fun callChanged(began: Boolean) {
        if (began && (hold.isHoldingCaptions || voice.isBusy)) voice.stop()
        if (!began && hold.callEnded(captions.isPaused)) captions.resume()
    }

    fun captionsCameOn() {
        if (captions.isListening && voice.isBusy && hold.captionsCameOnWhileSpeaking()) captions.pause()
    }

    private fun speakingChanged(speaking: Boolean) {
        onSpeaking?.invoke(speaking)
        if (speaking) return
        val generation = hold.speechWentQuiet() ?: return
        scope.launch {
            delay((SpeechPauseCoordinator.SETTLE_SECONDS * 1_000).toLong())
            if (hold.shouldResume(generation, voice.isBusy, captions.isPaused, captions.duringCall)) captions.resume()
        }
    }

    companion object {
        val REPEAT_REQUEST: String get() = tr("סליחה, אפשר לחזור על זה?", "Sorry, could you say that again?")
    }
}
