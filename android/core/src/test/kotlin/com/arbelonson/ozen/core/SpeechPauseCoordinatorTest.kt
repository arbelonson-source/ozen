package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechPauseCoordinatorTest {
    private class FakeSynthesizer : SpeechSynthesizing {
        override var isSpeaking = false
        override var isBusy = false
        override var hasHebrewVoice = true
        override var onSpeakingChanged: ((Boolean) -> Unit)? = null
        override fun refreshVoice() {}
        override fun speak(text: String, rate: Float) {}
        override fun stop() {}
    }

    @Test
    fun `speaking over live captions pauses them, and they return once speech settles`() {
        val coordinator = SpeechPauseCoordinator()
        val pause = coordinator.willSpeak(captionsListening = true)
        assertTrue(pause)
        val generation = coordinator.speechWentQuiet()
        assertNotNull(generation)
        val resume = coordinator.shouldResume(generation = generation ?: -1, synthesizerBusy = false, captionsPaused = true)
        assertTrue(resume)
        assertFalse(coordinator.isHoldingCaptions)
    }

    @Test
    fun `a phrase a phone call cut short keeps captions held through the call, and they return when it ends`() {
        val coordinator = SpeechPauseCoordinator()
        coordinator.willSpeak(captionsListening = true)
        val generation = coordinator.speechWentQuiet()
        val resumeMidCall = coordinator.shouldResume(generation = generation ?: -1, synthesizerBusy = false, captionsPaused = true, duringCall = true)
        assertFalse(resumeMidCall)
        assertTrue(coordinator.isHoldingCaptions)
        val resumeAtEnd = coordinator.callEnded(captionsPaused = true)
        assertTrue(resumeAtEnd)
        assertFalse(coordinator.isHoldingCaptions)
        val resumeAgain = coordinator.callEnded(captionsPaused = true)
        assertFalse(resumeAgain)
    }

    @Test
    fun `captions taken in hand during the call stay as she left them when it ends`() {
        val coordinator = SpeechPauseCoordinator()
        coordinator.willSpeak(captionsListening = true)
        val generation = coordinator.speechWentQuiet()
        coordinator.shouldResume(generation = generation ?: -1, synthesizerBusy = false, captionsPaused = true, duringCall = true)
        coordinator.userTookControl()
        val resume = coordinator.callEnded(captionsPaused = true)
        assertFalse(resume)
    }

    @Test
    fun `a second phrase tapped mid-speech keeps captions paused until the second one ends`() {
        val coordinator = SpeechPauseCoordinator()
        coordinator.willSpeak(captionsListening = true)
        // Second tap: captions are already paused (not listening).
        val pauseAgain = coordinator.willSpeak(captionsListening = false)
        assertFalse(pauseAgain)
        // The first utterance's cancel arrives; the second is queued.
        val afterCancel = coordinator.speechWentQuiet()
        val resumeOnCancel = coordinator.shouldResume(generation = afterCancel ?: -1, synthesizerBusy = true, captionsPaused = true)
        assertFalse(resumeOnCancel)
        assertTrue(coordinator.isHoldingCaptions)
        // The second utterance finishes.
        val afterFinish = coordinator.speechWentQuiet()
        val resumeOnFinish = coordinator.shouldResume(generation = afterFinish ?: -1, synthesizerBusy = false, captionsPaused = true)
        assertTrue(resumeOnFinish)
    }

    @Test
    fun `a quiet report that predates a newer request is ignored even if the synthesizer looks idle`() {
        val coordinator = SpeechPauseCoordinator()
        coordinator.willSpeak(captionsListening = true)
        val stale = coordinator.speechWentQuiet() ?: -1
        coordinator.willSpeak(captionsListening = false)
        val resume = coordinator.shouldResume(generation = stale, synthesizerBusy = false, captionsPaused = true)
        assertFalse(resume)
        assertTrue(coordinator.isHoldingCaptions)
    }

    @Test
    fun `speaking while captions are off never turns them on`() {
        val coordinator = SpeechPauseCoordinator()
        val pause = coordinator.willSpeak(captionsListening = false)
        assertFalse(pause)
        assertNull(coordinator.speechWentQuiet())
    }

    @Test
    fun `captions that finish starting up mid-phrase are held, and return when the phrase ends`() {
        val coordinator = SpeechPauseCoordinator()
        coordinator.willSpeak(captionsListening = false)
        val hold = coordinator.captionsCameOnWhileSpeaking()
        assertTrue(hold)
        val generation = coordinator.speechWentQuiet()
        val resume = coordinator.shouldResume(generation = generation ?: -1, synthesizerBusy = false, captionsPaused = true)
        assertTrue(resume)
    }

    @Test
    fun `captions turned on by hand mid-phrase are not held - the next phrase asked for starts afresh`() {
        val coordinator = SpeechPauseCoordinator()
        coordinator.willSpeak(captionsListening = false)
        coordinator.userTookControl()
        val holdAfterManualStart = coordinator.captionsCameOnWhileSpeaking()
        assertFalse(holdAfterManualStart)
        assertFalse(coordinator.isHoldingCaptions)

        coordinator.willSpeak(captionsListening = false)
        val holdForNewPhrase = coordinator.captionsCameOnWhileSpeaking()
        assertTrue(holdForNewPhrase)
    }

    @Test
    fun `a manual pause, stop or resume during speech cancels the automatic resume`() {
        val coordinator = SpeechPauseCoordinator()
        coordinator.willSpeak(captionsListening = true)
        val generation = coordinator.speechWentQuiet() ?: -1
        coordinator.userTookControl()
        val resume = coordinator.shouldResume(generation = generation, synthesizerBusy = false, captionsPaused = true)
        assertFalse(resume)
    }

    @Test
    fun `if captions are no longer paused when speech settles (restarted, failed), nothing is resumed`() {
        val coordinator = SpeechPauseCoordinator()
        coordinator.willSpeak(captionsListening = true)
        val generation = coordinator.speechWentQuiet() ?: -1
        val resume = coordinator.shouldResume(generation = generation, synthesizerBusy = false, captionsPaused = false)
        assertFalse(resume)
        assertFalse(coordinator.isHoldingCaptions)
    }

    @Test
    fun `without a Hebrew voice only Hebrew text is held back - English still has a voice`() {
        val synthesizer = FakeSynthesizer()
        assertTrue(synthesizer.canSay("שלום"))
        assertTrue(synthesizer.canSay("Hello"))
        synthesizer.hasHebrewVoice = false
        assertFalse(synthesizer.canSay("שלום"))
        assertFalse(synthesizer.canSay("OK, תודה"))
        assertTrue(synthesizer.canSay("Hello"))
        Localization.withLanguage(UILanguage.Hebrew) { assertFalse(synthesizer.canSay("123")) }
        Localization.withLanguage(UILanguage.English) { assertTrue(synthesizer.canSay("123")) }
        assertEquals(0.35, SpeechPauseCoordinator.SETTLE_SECONDS)
    }
}
