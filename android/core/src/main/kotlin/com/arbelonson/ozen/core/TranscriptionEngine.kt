package com.arbelonson.ozen.core

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Which speech-to-text engine is producing tokens. Kept as a plain enum
 * (rather than inferring it from the concrete type) so it can be stored in
 * `AppSettings`, shown in Settings, and switched by the user at runtime.
 */
enum class TranscriptionEngineKind(val rawValue: String) {
    WhisperKit("whisperKit"),
    AppleSpeech("appleSpeech"),

    /** A speech model on the internet (see `CloudSpeech`). */
    Cloud("cloud"),

    /** The family's own GPU computer (see `HomeServer`). */
    HomeServer("homeServer");

    /**
     * The name Settings gives it, in the app's language: a saved
     * conversation's details showed "Home server" under Hebrew captions.
     */
    val displayName: String get() = displayName(Localization.language)

    fun displayName(language: UILanguage): String = when (this) {
        WhisperKit -> tr("Whisper (במכשיר)", "Whisper (on device)", language)
        AppleSpeech -> tr("זיהוי הדיבור של אפל", "Apple's speech recognition", language)
        Cloud -> tr("תמלול בענן", "Cloud transcription", language)
        HomeServer -> tr("המחשב בבית", "Home computer", language)
    }

    companion object {
        fun fromRawValue(rawValue: String): TranscriptionEngineKind? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * One update from an engine about a single in-progress or finished
 * utterance. Engines emit many of these per utterance as more audio
 * arrives, the same [utteranceID] with growing or changing [text], and
 * mark the last one [isFinal] when the utterance is done. `CaptionStabilizer`
 * is what turns this stream into stable, displayable segments.
 */
data class TranscriptToken(
    val utteranceID: UUID,
    val text: String,
    val isFinal: Boolean,
    val timestamp: Double,
    val speakerClusterID: Int? = null,
    /**
     * Engine-reported confidence in 0...1 when the engine has one (Apple
     * Speech reports per-segment confidence; Whisper exposes log-probs that
     * get mapped into this range). Null means the engine said nothing.
     */
    val confidence: Float? = null,
    /**
     * The engine heard a different person start talking here, in the same
     * stretch of audio as the line before (see `CloudSpeech.turns`). The
     * voice heard just before belongs to that other line, not this one.
     */
    val startsNewSpeakerTurn: Boolean = false,
    /**
     * The words the engine was least sure of, when it can tell one word
     * from another (see `UncertainWords`). Empty means nothing to mark.
     */
    val uncertainWords: List<String> = emptyList(),
    /** Set by the pipeline, which knows which engine and model are running. */
    val scoredBy: CaptionConfidence.Scorer? = null,
)

/**
 * What an engine is doing while it gets ready. Whisper has to download
 * hundreds of megabytes of model on first launch and then compile it for
 * the chip, which can take minutes, and the very first version of the app
 * showed nothing at all during that time, which read as "broken". Every
 * stage is reported so the screen can say exactly what's happening.
 */
data class EnginePreparationProgress(
    val stage: Stage,
    /** 0...1 when the engine can measure it (downloads), null when it can't. */
    val fraction: Double? = null,
    /**
     * Free-form technical detail for the diagnostics screen, e.g. the model
     * variant being fetched. Not user-facing copy.
     */
    val detail: String? = null,
    /**
     * The model has not been loaded on this phone since it was downloaded
     * or since the system was last updated: the load compiles it for the
     * chip and takes minutes instead of seconds, and the screen should say
     * so rather than look stuck.
     */
    val isFirstTime: Boolean = false,
    /**
     * A load that is not a first set-up but has run for a while anyway (the
     * system threw the phone's compiled copy away to free space, say): set
     * by the pipeline, so the screen stops saying "just a moment".
     */
    val isTakingLong: Boolean = false,
) {
    enum class Stage(val rawValue: String) {
        CheckingSupport("checkingSupport"),
        RequestingPermission("requestingPermission"),
        DownloadingModel("downloadingModel"),
        LoadingModel("loadingModel"),
        WarmingUp("warmingUp"),
    }

    /**
     * Whether this update is worth showing after [after], which went on
     * screen at [shownAt]: another step or model, another whole percent
     * (what the screen shows), or a second later. A download reports every
     * chunk off the network, and each report redrew the caption screen.
     */
    fun isNews(after: EnginePreparationProgress, shownAt: Double, now: Double): Boolean {
        val shownFraction = after.fraction
        val own = fraction
        if (stage != after.stage || detail != after.detail || own == null || shownFraction == null) {
            return this != after
        }
        return percent(own) != percent(shownFraction) || now - shownAt >= FRACTION_INTERVAL_SECONDS
    }

    companion object {
        /**
         * How often a download's fraction alone is put on screen when it
         * hasn't reached another whole percent, so the time left stays fresh.
         */
        const val FRACTION_INTERVAL_SECONDS: Double = 1.0

        private fun percent(fraction: Double): Int = Math.rint(fraction * 100).toInt()
    }
}

/**
 * Why an engine can't be used, structured so the UI can decide what to
 * offer (a retry button, a "open Settings" button, a suggestion to try the
 * other engine) instead of pattern-matching on English error text.
 */
data class EngineUnavailability(
    val kind: Kind,
    val detail: String,
    /** For `WaitingForWiFi` and `NotEnoughStorage`: how big the download is, so the screen can say. */
    val downloadMegabytes: Int? = null,
    /** For `NotEnoughStorage`: how much more room has to be freed, when known. */
    val missingMegabytes: Int? = null,
) : Exception() {
    enum class Kind(val rawValue: String) {
        PermissionDenied("permissionDenied"),
        LanguageNotSupportedOnDevice("languageNotSupportedOnDevice"),
        ModelDownloadFailed("modelDownloadFailed"),
        ModelLoadFailed("modelLoadFailed"),

        /**
         * The model still has to be downloaded, the phone is on cellular
         * data or Low Data Mode, and nobody said that's fine.
         */
        WaitingForWiFi("waitingForWiFi"),

        /** The phone doesn't have room for the model (see `StorageSpaceGate`). */
        NotEnoughStorage("notEnoughStorage"),

        /** Cloud captions have no key, or the cloud service turned the key down. */
        CloudKeyNeeded("cloudKeyNeeded"),

        /** The cloud key has used up its credit or spending limit. */
        CloudOutOfCredit("cloudOutOfCredit"),

        /** Cloud captions can't reach the internet. */
        NoInternet("noInternet"),

        /** The home server didn't answer, or the connection to it dropped. */
        HomeServerUnreachable("homeServerUnreachable"),

        /** The home server turned the pairing code down, or there is none. */
        HomeServerRejected("homeServerRejected"),
        TemporarilyUnavailable("temporarilyUnavailable"),
        Other("other"),
    }

    companion object {
        internal fun homeServerUnreachable(detail: String): EngineUnavailability =
            EngineUnavailability(Kind.HomeServerUnreachable, detail)

        internal fun homeServerRejected(detail: String): EngineUnavailability =
            EngineUnavailability(Kind.HomeServerRejected, detail)
    }
}

/**
 * Whether an engine can actually be used right now, in this language, on
 * this device. Distinct from "engine exists": the on-device model for a
 * language may simply not be installed, and the app needs to say so rather
 * than silently falling back to a server-based mode that would break the
 * on-device-only requirement without telling anyone.
 */
sealed class EngineAvailability {
    data object Available : EngineAvailability()

    data class Unavailable(val why: EngineUnavailability) : EngineAvailability()

    val unavailability: EngineUnavailability? get() = (this as? Unavailable)?.why

    companion object {
        fun unavailable(kind: EngineUnavailability.Kind, detail: String): EngineAvailability =
            Unavailable(EngineUnavailability(kind, detail))
    }
}

/**
 * A live, streaming speech-to-text engine. The platform engines live in the
 * app; this interface has no platform dependency so the rest of the
 * pipeline (`CaptionStabilizer`, `CaptionPipeline`) can be tested against a
 * fake engine with no audio or model involved.
 *
 * The slow calls suspend. [stream] reads [audio] and the returned flow
 * emits tokens as they become available; an engine that fails throws from
 * collecting it, and cancelling the collector stops the engine.
 */
interface TranscriptionEngine {
    val kind: TranscriptionEngineKind

    /**
     * Does whatever slow work is needed before [stream] can produce tokens
     * (permissions, model download, model load), reporting each stage
     * through [progress]. Must be safe to call again on an engine that's
     * already prepared: the pipeline caches engine instances across
     * restarts precisely so a second call is instant.
     */
    suspend fun prepare(languageCode: String, progress: (EnginePreparationProgress) -> Unit): EngineAvailability

    /**
     * Consumes rolling PCM float buffers and yields tokens as they become
     * available. [audio] is expected to be short, sequential chunks (a
     * couple hundred ms to a couple seconds each) rather than one big
     * buffer per utterance: that's what makes the result "live".
     */
    fun stream(languageCode: String, audio: Flow<FloatArray>): Flow<TranscriptToken>

    /**
     * How much [prepare] would have to download first, in megabytes, or
     * null when nothing big is needed (the model is already on the phone,
     * or the engine doesn't download). Asked before [prepare] so a large
     * download can wait for Wi-Fi.
     */
    suspend fun pendingDownloadMegabytes(): Int? = null

    /**
     * The free space that download needs at its peak, in megabytes: more
     * than the download when the model is compiled on the phone beside its
     * packages. Defaults to the download itself.
     */
    suspend fun pendingInstallMegabytes(): Int? = pendingDownloadMegabytes()

    /**
     * Whether a download that [prepare] starts may use cellular data or
     * Low Data Mode. Set before every [prepare]: the check before a
     * download only sees the connection at that moment, and a download
     * that began on Wi-Fi must stop, not move to the phone plan, when
     * Wi-Fi drops. Engines that download nothing ignore it.
     */
    suspend fun setCellularDownloadAllowed(allowed: Boolean) {}

    /**
     * Stops a model download [prepare] has running, if any, so that a
     * preparation nobody wants any more ends soon instead of finishing the
     * download first. A model already loading is left to finish. Engines
     * that download nothing ignore it.
     */
    suspend fun cancelDownload() {}

    /**
     * Names and words to bias recognition towards (see `VocabularyHints`).
     * Called before every [stream] and again whenever the user edits the
     * list mid-conversation; engines that can't use hints ignore it.
     */
    suspend fun setVocabulary(terms: List<String>) {}

    /**
     * What the engine knows about how its work has gone (how long passes
     * take, how much it threw away), for the journal when a problem is
     * marked. Null from engines that keep no such count.
     */
    suspend fun diagnosticsSummary(): String? = null

    /** [prepare] without caring about progress, for callers (and tests) that only want the yes/no answer. */
    suspend fun checkAvailability(languageCode: String): EngineAvailability = prepare(languageCode) {}

    /**
     * Gives back what the engine holds outside Kotlin's memory, the
     * on-phone model above all, for the pipeline to call on an engine it
     * drops. On the iPhone the model goes with the last reference to its
     * engine; here nothing frees it but this, so an engine dropped without
     * it kept its model's hundreds of megabytes until the app ended. A
     * released engine loads again on its next [prepare].
     */
    fun release() {}
}
