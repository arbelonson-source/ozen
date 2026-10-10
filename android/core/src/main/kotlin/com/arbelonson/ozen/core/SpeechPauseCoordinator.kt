package com.arbelonson.ozen.core

/**
 * Anything that can say text aloud. The real one wraps the platform's
 * text-to-speech engine in the Android app layer; tests use a scripted fake.
 */
interface SpeechSynthesizing {
    /** Speaking right now (drives the UI's speaker icon). */
    val isSpeaking: Boolean

    /**
     * Speaking *or* holding a queued utterance that hasn't started yet.
     * A cancel callback can arrive while the next phrase is already
     * queued, and only this tells the two apart.
     */
    val isBusy: Boolean
    val hasHebrewVoice: Boolean
    var onSpeakingChanged: ((Boolean) -> Unit)?

    /**
     * Re-checks which Hebrew voice is installed. [hasHebrewVoice] is only
     * ever set once at launch otherwise, so installing or removing a
     * voice in the system settings and coming straight back would go unnoticed
     * until the app relaunches.
     */
    fun refreshVoice()

    fun speak(text: String, rate: Float)
    fun stop()

    /**
     * Whether the phone has a voice for [language]; without one the system
     * reads the text with another language's voice, which nobody understands.
     */
    fun hasVoice(language: UILanguage): Boolean =
        if (language == UILanguage.Hebrew) hasHebrewVoice else true

    /**
     * True unless the app is in a language beyond Hebrew and English with
     * no voice on the phone. Hebrew has its own hint, and English always
     * has a voice.
     */
    fun hasVoiceOrOwnHint(appLanguage: UILanguage): Boolean =
        appLanguage == UILanguage.Hebrew || appLanguage == UILanguage.English || hasVoice(appLanguage)

    /**
     * Whether [text] would come out as speech someone can understand:
     * only text in a language the phone has no voice for is held back.
     */
    fun canSay(text: String): Boolean =
        hasVoice(UILanguage.forSpeaking(text, otherwise = Localization.language))
}

/**
 * Decides when captions pause for the phone's own voice and when they
 * come back.
 *
 * The naive rule, "resume when the synthesizer says it stopped", fails in
 * the most common case: tapping a second ready-made phrase while the
 * first is still playing. Starting the second cancels the first, the
 * cancel reports "stopped", captions resume, and the microphone captions
 * the phone saying the second phrase. So every request to speak bumps a
 * generation, a "went quiet" report waits a short settle time, and
 * captions resume only if nothing new was asked for in the meantime and
 * the synthesizer really is idle.
 */
class SpeechPauseCoordinator {
    /** True while captions are paused because of speech (and not by hand). */
    var isHoldingCaptions = false
        private set

    var generation = 0
        private set

    /**
     * Someone paused, resumed, started or stopped captions by hand since
     * the last phrase was asked for.
     */
    private var userChoseSinceSpeaking = false

    /**
     * Call before speaking. Returns true when captions are live and the
     * caller should pause them now.
     */
    fun willSpeak(captionsListening: Boolean): Boolean {
        generation += 1
        userChoseSinceSpeaking = false
        if (!captionsListening) return false
        isHoldingCaptions = true
        return true
    }

    /**
     * The synthesizer reported it stopped (finished or cancelled).
     * Returns the generation to re-check after [SETTLE_SECONDS], or null
     * when these captions aren't ours to bring back.
     */
    fun speechWentQuiet(): Int? = if (isHoldingCaptions) generation else null

    /**
     * After the settle delay. True means: resume captions now. Not during
     * a phone call: the call stops the phrase, and resuming then asked for
     * the microphone the call holds. The hold stays, and [callEnded]
     * brings captions back.
     */
    fun shouldResume(generation: Int, synthesizerBusy: Boolean, captionsPaused: Boolean, duringCall: Boolean = false): Boolean {
        if (!isHoldingCaptions || generation != this.generation || synthesizerBusy || duringCall) return false
        isHoldingCaptions = false
        return captionsPaused
    }

    /**
     * The call that cut a phrase short is over. True means: resume
     * captions now, as the phrase's end would have.
     */
    fun callEnded(captionsPaused: Boolean): Boolean {
        if (!isHoldingCaptions) return false
        isHoldingCaptions = false
        return captionsPaused
    }

    /**
     * The user paused, resumed, stopped or restarted by hand. Their
     * choice wins over any automatic resume still pending.
     */
    fun userTookControl() {
        isHoldingCaptions = false
        userChoseSinceSpeaking = true
    }

    /**
     * Captions came on while the phone was still talking: they were
     * still starting up (loading a model takes seconds) when the phrase
     * was asked for, or an automatic retry landed mid-phrase. True means:
     * pause them now, and they come back when the phone is done. Not when
     * someone turned them on by hand meanwhile; that choice wins.
     */
    fun captionsCameOnWhileSpeaking(): Boolean {
        if (userChoseSinceSpeaking) return false
        isHoldingCaptions = true
        return true
    }

    fun copy(): SpeechPauseCoordinator {
        val copy = SpeechPauseCoordinator()
        copy.isHoldingCaptions = isHoldingCaptions
        copy.generation = generation
        copy.userChoseSinceSpeaking = userChoseSinceSpeaking
        return copy
    }

    override fun equals(other: Any?): Boolean =
        other is SpeechPauseCoordinator && isHoldingCaptions == other.isHoldingCaptions &&
            generation == other.generation && userChoseSinceSpeaking == other.userChoseSinceSpeaking

    override fun hashCode(): Int {
        var result = isHoldingCaptions.hashCode()
        result = 31 * result + generation
        result = 31 * result + userChoseSinceSpeaking.hashCode()
        return result
    }

    companion object {
        /** Long enough for the speaker's tail to die away before the microphone opens again. */
        const val SETTLE_SECONDS: Double = 0.35
    }
}
