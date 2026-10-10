package com.arbelonson.ozen.core

import com.arbelonson.ozen.core.RecognitionRequestPolicy.ErrorResponse
import com.arbelonson.ozen.core.RecognitionRequestPolicy.RolloverReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecognitionRequestPolicyTest {
    private fun seconds(value: Double): Int = RecognitionRequestPolicy.samples(value)

    @Test
    fun `a pause after speech ends the utterance, but a pause before any speech doesn't`() {
        assertEquals(RolloverReason.PauseAfterSpeech, RecognitionRequestPolicy.rollover(samplesInRequest = seconds(4.0), samplesSinceSpeech = seconds(1.2), requestHasSpeech = true))
        assertNull(RecognitionRequestPolicy.rollover(samplesInRequest = seconds(4.0), samplesSinceSpeech = seconds(1.1), requestHasSpeech = true))
        assertNull(RecognitionRequestPolicy.rollover(samplesInRequest = seconds(4.0), samplesSinceSpeech = seconds(4.0), requestHasSpeech = false))
    }

    @Test
    fun `a long unbroken utterance is cut at the request limit`() {
        assertEquals(RolloverReason.TooLong, RecognitionRequestPolicy.rollover(samplesInRequest = seconds(45.0), samplesSinceSpeech = 0, requestHasSpeech = true))
    }

    @Test
    fun `close to the hard cap, a shorter pause is enough to cut cleanly instead of mid-word`() {
        assertEquals(RolloverReason.PauseNearLimit, RecognitionRequestPolicy.rollover(samplesInRequest = seconds(41.0), samplesSinceSpeech = seconds(0.5), requestHasSpeech = true))
        assertNull(RecognitionRequestPolicy.rollover(samplesInRequest = seconds(35.0), samplesSinceSpeech = seconds(0.5), requestHasSpeech = true))
        assertEquals(RolloverReason.TooLong, RecognitionRequestPolicy.rollover(samplesInRequest = seconds(45.0), samplesSinceSpeech = 0, requestHasSpeech = true))
    }

    @Test
    fun `silence restarts the request before the recognizer can time out`() {
        assertEquals(RolloverReason.Idle, RecognitionRequestPolicy.rollover(samplesInRequest = seconds(8.0), samplesSinceSpeech = seconds(8.0), requestHasSpeech = false))
        assertNull(RecognitionRequestPolicy.rollover(samplesInRequest = seconds(7.9), samplesSinceSpeech = seconds(7.9), requestHasSpeech = false))
    }

    @Test
    fun `errors from requests already ended on purpose are ignored`() {
        assertEquals(ErrorResponse.Ignore, RecognitionRequestPolicy.respond(isCurrentRequest = false, requestHadSpeech = true, requestAudioSeconds = 0.1, consecutiveFailures = 2))
    }

    @Test
    fun `a quiet room timing out is not a strike, however many times it happens`() {
        for (strikes in 0 until 10) {
            assertEquals(ErrorResponse.RestartQuietly, RecognitionRequestPolicy.respond(isCurrentRequest = true, requestHadSpeech = false, requestAudioSeconds = 5.0, consecutiveFailures = strikes))
        }
    }

    @Test
    fun `failing mid-speech or failing instantly counts, and the third strike gives up`() {
        assertEquals(ErrorResponse.RestartCounting, RecognitionRequestPolicy.respond(isCurrentRequest = true, requestHadSpeech = true, requestAudioSeconds = 6.0, consecutiveFailures = 0))
        assertEquals(ErrorResponse.RestartCounting, RecognitionRequestPolicy.respond(isCurrentRequest = true, requestHadSpeech = false, requestAudioSeconds = 0.3, consecutiveFailures = 1))
        assertEquals(ErrorResponse.GiveUp, RecognitionRequestPolicy.respond(isCurrentRequest = true, requestHadSpeech = false, requestAudioSeconds = 0.3, consecutiveFailures = 2))
    }
}
