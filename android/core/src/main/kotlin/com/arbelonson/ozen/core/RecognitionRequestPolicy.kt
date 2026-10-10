package com.arbelonson.ozen.core

object RecognitionRequestPolicy {
    const val SAMPLE_RATE = 16_000

    const val PAUSE_SECONDS = 1.2

    const val MAX_REQUEST_SECONDS = 45.0

    const val NEAR_LIMIT_GRACE_SECONDS = 5.0
    const val NEAR_LIMIT_PAUSE_SECONDS = 0.4

    const val IDLE_RESTART_SECONDS = 8.0

    const val FAST_FAILURE_SECONDS = 2.0
    const val MAX_CONSECUTIVE_FAILURES = 3

    enum class RolloverReason {
        PauseAfterSpeech,
        PauseNearLimit,
        TooLong,
        Idle,
    }

    fun rollover(samplesInRequest: Int, samplesSinceSpeech: Int, requestHasSpeech: Boolean): RolloverReason? {
        if (requestHasSpeech && samplesSinceSpeech >= samples(PAUSE_SECONDS)) return RolloverReason.PauseAfterSpeech
        if (requestHasSpeech &&
            samplesInRequest >= samples(MAX_REQUEST_SECONDS - NEAR_LIMIT_GRACE_SECONDS) &&
            samplesSinceSpeech >= samples(NEAR_LIMIT_PAUSE_SECONDS)
        ) {
            return RolloverReason.PauseNearLimit
        }
        if (samplesInRequest >= samples(MAX_REQUEST_SECONDS)) return RolloverReason.TooLong
        if (!requestHasSpeech && samplesInRequest >= samples(IDLE_RESTART_SECONDS)) return RolloverReason.Idle
        return null
    }

    enum class ErrorResponse {
        Ignore,
        RestartQuietly,
        RestartCounting,
        GiveUp,
    }

    fun respond(
        isCurrentRequest: Boolean,
        requestHadSpeech: Boolean,
        requestAudioSeconds: Double,
        consecutiveFailures: Int,
    ): ErrorResponse {
        if (!isCurrentRequest) return ErrorResponse.Ignore
        if (!requestHadSpeech && requestAudioSeconds >= FAST_FAILURE_SECONDS) {
            return ErrorResponse.RestartQuietly
        }
        return if (consecutiveFailures + 1 >= MAX_CONSECUTIVE_FAILURES) ErrorResponse.GiveUp else ErrorResponse.RestartCounting
    }

    internal fun samples(seconds: Double): Int = (seconds * SAMPLE_RATE.toDouble()).toInt()
}
