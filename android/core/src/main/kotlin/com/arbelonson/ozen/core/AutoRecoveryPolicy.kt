package com.arbelonson.ozen.core

/**
 * When a failed pipeline should try again by itself.
 *
 * The reader of this app is not going to diagnose a hiccup. If the
 * recognizer drops out in the middle of a conversation, captions that stay
 * dead until someone notices the red status and taps it are, for her,
 * captions that stopped working. So failures that can clear up on their
 * own get retried automatically, sooner for a glitch and patiently for a
 * download waiting on Wi-Fi. Failures only a person can fix (a denied
 * permission, a language the device doesn't have) never are.
 *
 * A value: [copy] gives an independent policy, the way Swift's struct
 * copies on assignment.
 */
class AutoRecoveryPolicy(
    var glitchDelays: List<Double> = listOf(1.0, 3.0, 8.0, 20.0),
    var downloadDelays: List<Double> = listOf(15.0, 30.0, 60.0, 120.0, 300.0, 600.0),
    /**
     * Listening this long without trouble means the next failure is a new
     * problem, not the same one again, so the attempt count starts over.
     */
    var healthyListeningSeconds: Double = 60.0,
) {
    enum class Schedule {
        /** Audio or recognizer glitch: retry quickly a few times. */
        Glitch,

        /** Download failed, usually no connection: retry for a long while. */
        Download,

        /** A model that failed to load rarely fixes itself: try twice. */
        LoadFailure,

        /** Needs the person (system Settings, a different engine). */
        Never,
    }

    /**
     * A problem that keeps changing shape (a download that fails, then a
     * corrupt half-download that won't load, then fails again) would
     * otherwise get a fresh budget every time it switches schedule and
     * never stop retrying. This bounds the whole streak regardless of how
     * many different schedules it passes through.
     */
    var maxAttemptsAcrossSchedules: Int = 10
    var attempts: Int = 0
        private set
    var overallAttempts: Int = 0
        private set

    /**
     * The schedule `attempts` counts against. A retry for a glitch that
     * turns out to actually be, say, a model failing to load is a new
     * problem with its own two tries, not a continuation of the glitch's
     * unrelated backoff.
     */
    private var lastSchedule: Schedule? = null

    /**
     * Seconds to wait before retrying [failure], or null when it's time to
     * stop and let the person decide. Each call counts as one attempt.
     */
    fun nextDelay(failure: PipelineFailure): Double? {
        val schedule = schedule(failure)
        if (schedule != lastSchedule) {
            attempts = 0
            lastSchedule = schedule
        }
        val delays = when (schedule) {
            Schedule.Never -> return null
            Schedule.Glitch -> glitchDelays
            Schedule.Download -> downloadDelays
            Schedule.LoadFailure -> glitchDelays.take(2)
        }
        if (attempts >= delays.size || overallAttempts >= maxAttemptsAcrossSchedules) return null
        val delay = delays[attempts]
        attempts += 1
        overallAttempts += 1
        return delay
    }

    fun reset() {
        attempts = 0
        overallAttempts = 0
        lastSchedule = null
    }

    fun copy(): AutoRecoveryPolicy {
        val other = AutoRecoveryPolicy(glitchDelays, downloadDelays, healthyListeningSeconds)
        other.maxAttemptsAcrossSchedules = maxAttemptsAcrossSchedules
        other.attempts = attempts
        other.overallAttempts = overallAttempts
        other.lastSchedule = lastSchedule
        return other
    }

    override fun equals(other: Any?): Boolean =
        other is AutoRecoveryPolicy &&
            glitchDelays == other.glitchDelays &&
            downloadDelays == other.downloadDelays &&
            healthyListeningSeconds == other.healthyListeningSeconds &&
            maxAttemptsAcrossSchedules == other.maxAttemptsAcrossSchedules &&
            attempts == other.attempts &&
            overallAttempts == other.overallAttempts &&
            lastSchedule == other.lastSchedule

    override fun hashCode(): Int = listOf(
        glitchDelays, downloadDelays, healthyListeningSeconds, maxAttemptsAcrossSchedules,
        attempts, overallAttempts, lastSchedule,
    ).hashCode()

    companion object {
        /** Never retries. For tests that want a failure to stay put. */
        fun disabled(): AutoRecoveryPolicy = AutoRecoveryPolicy(glitchDelays = emptyList(), downloadDelays = emptyList())

        fun schedule(failure: PipelineFailure): Schedule = when (failure.kind) {
            PipelineFailure.Kind.MicrophonePermissionDenied -> Schedule.Never
            PipelineFailure.Kind.AudioSessionFailed,
            PipelineFailure.Kind.NoAudioInputs,
            PipelineFailure.Kind.TranscriptionStopped,
            -> Schedule.Glitch
            PipelineFailure.Kind.EngineUnavailable -> when (failure.engineUnavailability?.kind) {
                EngineUnavailability.Kind.PermissionDenied,
                EngineUnavailability.Kind.LanguageNotSupportedOnDevice,
                EngineUnavailability.Kind.CloudKeyNeeded,
                EngineUnavailability.Kind.CloudOutOfCredit,
                EngineUnavailability.Kind.HomeServerRejected,
                -> Schedule.Never
                // Retrying on a timer would only ask the same question of
                // the same cellular connection. The pipeline retries when
                // the connection changes instead.
                EngineUnavailability.Kind.WaitingForWiFi -> Schedule.Never
                // Only the person can free up room; a timer would just fill
                // the phone again. The pipeline checks again when the app
                // comes back on screen.
                EngineUnavailability.Kind.NotEnoughStorage -> Schedule.Never
                // A timer can't put the file on the phone.
                EngineUnavailability.Kind.ModelNotOnDevice -> Schedule.Never
                EngineUnavailability.Kind.ModelDownloadFailed -> Schedule.Download
                EngineUnavailability.Kind.ModelLoadFailed -> Schedule.LoadFailure
                EngineUnavailability.Kind.NoInternet,
                EngineUnavailability.Kind.HomeServerUnreachable,
                EngineUnavailability.Kind.TemporarilyUnavailable,
                EngineUnavailability.Kind.Other,
                null,
                -> Schedule.Glitch
            }
        }
    }
}

/** An automatic retry the pipeline has lined up. */
data class ScheduledRetry(
    /** Wall-clock time the retry will run. */
    val at: Double,
    /** 1 for the first automatic retry after a failure, and so on. */
    val attempt: Int,
)
