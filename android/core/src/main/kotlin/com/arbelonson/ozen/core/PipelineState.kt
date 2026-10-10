package com.arbelonson.ozen.core

import kotlin.math.max

/**
 * Something that stopped the caption pipeline from reaching (or staying
 * in) the listening state. Structured by kind so the screen can show the
 * right recovery action: a permission denial can only be fixed in the
 * system Settings app, while a failed model download just needs a retry
 * once there's Wi-Fi.
 */
data class PipelineFailure(
    val kind: Kind,
    /**
     * Technical detail (underlying error text, model name) for the
     * diagnostics screen. Never shown as the primary message.
     */
    val detail: String,
    val engineUnavailability: EngineUnavailability? = null,
) : Exception() {
    enum class Kind(val rawValue: String) {
        MicrophonePermissionDenied("microphonePermissionDenied"),
        EngineUnavailable("engineUnavailable"),
        AudioSessionFailed("audioSessionFailed"),
        NoAudioInputs("noAudioInputs"),
        TranscriptionStopped("transcriptionStopped"),
    }

    /**
     * True when a "try again" button inside the app can plausibly help.
     * A denied permission can't be fixed from inside the app at all, so
     * offering a retry there would just be a button that does nothing.
     */
    val isRetryableInApp: Boolean
        get() = when (kind) {
            Kind.MicrophonePermissionDenied -> false
            Kind.EngineUnavailable -> engineUnavailability?.kind != EngineUnavailability.Kind.PermissionDenied
            Kind.AudioSessionFailed, Kind.NoAudioInputs, Kind.TranscriptionStopped -> true
        }

    val needsSystemSettings: Boolean get() = !isRetryableInApp

    /**
     * Whether switching to the other engine is a sensible suggestion,
     * e.g. Apple's on-device Hebrew model missing on this iOS version is
     * exactly the case Whisper exists for.
     */
    val suggestsOtherEngine: Boolean
        get() {
            if (kind != Kind.EngineUnavailable) return false
            val why = engineUnavailability ?: return false
            return when (why.kind) {
                EngineUnavailability.Kind.LanguageNotSupportedOnDevice,
                EngineUnavailability.Kind.ModelDownloadFailed,
                EngineUnavailability.Kind.ModelLoadFailed,
                EngineUnavailability.Kind.ModelNotOnDevice,
                EngineUnavailability.Kind.NotEnoughStorage,
                EngineUnavailability.Kind.NoInternet,
                EngineUnavailability.Kind.HomeServerUnreachable,
                -> true
                EngineUnavailability.Kind.PermissionDenied,
                EngineUnavailability.Kind.WaitingForWiFi,
                EngineUnavailability.Kind.CloudKeyNeeded,
                EngineUnavailability.Kind.CloudOutOfCredit,
                EngineUnavailability.Kind.HomeServerRejected,
                EngineUnavailability.Kind.TemporarilyUnavailable,
                EngineUnavailability.Kind.Other,
                -> false
            }
        }
}

/**
 * The single source of truth for what the live screen should show in its
 * status slot. Every transition the pipeline makes lands here, in order,
 * so there's no moment where the app is doing something (like a multi-
 * minute model download) that the screen doesn't reflect.
 */
sealed class PipelinePhase {
    data object Idle : PipelinePhase()

    data object RequestingMicrophonePermission : PipelinePhase()

    data class PreparingEngine(val progress: EnginePreparationProgress) : PipelinePhase()

    data object StartingAudio : PipelinePhase()

    data object Listening : PipelinePhase()

    data object Paused : PipelinePhase()

    data class Failed(val reason: PipelineFailure) : PipelinePhase()

    val isListening: Boolean get() = this == Listening

    /**
     * True while `start()` is still working through its steps; the UI
     * uses this to disable actions that would race the startup sequence.
     */
    val isTransitioning: Boolean
        get() = when (this) {
            RequestingMicrophonePermission, is PreparingEngine, StartingAudio -> true
            Idle, Listening, Paused, is Failed -> false
        }

    val failure: PipelineFailure? get() = (this as? Failed)?.reason

    val preparationProgress: EnginePreparationProgress? get() = (this as? PreparingEngine)?.progress

    /**
     * The phase without a download's fraction, for work to do when a step
     * begins or ends: the fraction changes many times a second, and a
     * screen reacting to each one rescanned the disk or saved the
     * conversation with every percent.
     */
    val step: PipelinePhase
        get() = if (this is PreparingEngine) PreparingEngine(progress.copy(fraction = null)) else this
}

/**
 * Running counters for the diagnostics screen. Cheap to keep and
 * genuinely useful when the owner reports "it went quiet": the numbers
 * say whether audio stopped arriving, tokens stopped arriving, or the
 * screen stopped updating.
 */
class PipelineStats {
    var sessionStartedAt: Double? = null
    var audioChunksReceived: Int = 0
    var audioSecondsReceived: Double = 0.0
    var tokensReceived: Int = 0
    var segmentsCommitted: Int = 0
    var lastTokenAt: Double? = null
    var lastAudioAt: Double? = null
    var engineRestarts: Int = 0
    var inputChanges: Int = 0
    var speakerClustersOpened: Int = 0

    /**
     * Times the microphone stopped delivering audio mid-session and
     * capture was restarted because of it.
     */
    var audioStalls: Int = 0

    /**
     * Chunks with a NaN or infinite sample in them, passed on as silence
     * (see `AudioFanOut`). Climbing means a microphone sending damaged
     * audio.
     */
    var glitchedAudioChunks: Int = 0

    /** A caption line is still being written (not yet final). */
    var hasOpenLine: Boolean = false

    /**
     * Sound alerts are being listened for right now. False while
     * captions run means the classifier stopped on its own.
     */
    var soundDetectionRunning: Boolean = false

    /** How loud the microphone's chunks have been since launch. */
    var inputLevels: AudioLevelHistogram = AudioLevelHistogram()

    /** Chunks the voice detector counted as someone talking. */
    var speechChunks: Int = 0

    /**
     * The voice detector's noise floor and the margin above it speech
     * needs, in dB, as of the latest chunk (see `EnergyVoiceDetector`).
     */
    var noiseFloorDecibels: Double? = null
    var noiseMarginDecibels: Double? = null

    /**
     * The share of audio the voice detector counted as speech, 0...1.
     * Near zero through a conversation means speech arrives too quietly
     * to clear its threshold.
     */
    val speechShare: Double?
        get() {
            if (inputLevels.total <= 0) return null
            return speechChunks.toDouble() / inputLevels.total.toDouble()
        }

    /**
     * Seconds between the newest audio and the newest token while a line
     * is still being written: a rough, honest "how far behind is the
     * caption" number. With every line final, the captions have caught up
     * and the lag is 0; measuring then would count the silence since the
     * last word as delay.
     */
    val captionLagSeconds: Double?
        get() {
            val audio = lastAudioAt ?: return null
            val token = lastTokenAt ?: return null
            if (!hasOpenLine) return 0.0
            return max(0.0, audio - token)
        }

    /** A copy that no later change to this one reaches, as a Swift struct copies. */
    fun copy(): PipelineStats {
        val copy = PipelineStats()
        copy.sessionStartedAt = sessionStartedAt
        copy.audioChunksReceived = audioChunksReceived
        copy.audioSecondsReceived = audioSecondsReceived
        copy.tokensReceived = tokensReceived
        copy.segmentsCommitted = segmentsCommitted
        copy.lastTokenAt = lastTokenAt
        copy.lastAudioAt = lastAudioAt
        copy.engineRestarts = engineRestarts
        copy.inputChanges = inputChanges
        copy.speakerClustersOpened = speakerClustersOpened
        copy.audioStalls = audioStalls
        copy.glitchedAudioChunks = glitchedAudioChunks
        copy.hasOpenLine = hasOpenLine
        copy.soundDetectionRunning = soundDetectionRunning
        copy.inputLevels = inputLevels.copy()
        copy.speechChunks = speechChunks
        copy.noiseFloorDecibels = noiseFloorDecibels
        copy.noiseMarginDecibels = noiseMarginDecibels
        return copy
    }

    private fun fields(): List<Any?> = listOf(
        sessionStartedAt, audioChunksReceived, audioSecondsReceived, tokensReceived, segmentsCommitted,
        lastTokenAt, lastAudioAt, engineRestarts, inputChanges, speakerClustersOpened, audioStalls,
        glitchedAudioChunks, hasOpenLine, soundDetectionRunning, inputLevels, speechChunks,
        noiseFloorDecibels, noiseMarginDecibels,
    )

    override fun equals(other: Any?): Boolean = other is PipelineStats && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()
}
