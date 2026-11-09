package com.arbelonson.ozen

import com.arbelonson.ozen.core.SpeechSynthesizing

internal class FakeVoice : SpeechSynthesizing {
    override var isSpeaking = false
    override var isBusy = false
    override var hasHebrewVoice = true
    override var onSpeakingChanged: ((Boolean) -> Unit)? = null
    val said = mutableListOf<String>()
    val rates = mutableListOf<Float>()

    override fun refreshVoice() = Unit

    override fun speak(text: String, rate: Float) {
        said += text
        rates += rate
        isBusy = true
        isSpeaking = true
        onSpeakingChanged?.invoke(true)
    }

    override fun stop() = finish()

    fun finish() {
        isBusy = false
        isSpeaking = false
        onSpeakingChanged?.invoke(false)
    }
}
