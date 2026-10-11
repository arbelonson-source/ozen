package com.arbelonson.ozen

import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SpeechSynthesizing
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SpokenRepliesTest {
    private class FakeVoice : SpeechSynthesizing {
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

    private class FakeCaptions : SpokenReplies.Captions {
        override var isListening = true
        override var isPaused = false
        override var duringCall = false
        var pauses = 0
        var resumes = 0

        override fun pause() {
            pauses += 1
            isListening = false
            isPaused = true
        }

        override fun resume() {
            resumes += 1
            isListening = true
            isPaused = false
        }
    }

    private val voice = FakeVoice()
    private val captions = FakeCaptions()

    private fun TestScope.settle() {
        advanceTimeBy(400)
        runCurrent()
    }

    @Test
    fun `captions pause while the phone talks and come back once its voice has died away`() = runTest {
        val replies = SpokenReplies(voice, this, captions)
        assertTrue(replies.speak("  Two pills after dinner  ", rate = 0.45f))
        assertEquals(listOf("Two pills after dinner"), voice.said)
        assertEquals(listOf(0.45f), voice.rates)
        assertEquals(1, captions.pauses)
        voice.finish()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(0, captions.resumes)
        settle()
        assertEquals(1, captions.resumes)
    }

    @Test
    fun `a second phrase cutting off the first keeps captions paused until the second is said`() = runTest {
        val replies = SpokenReplies(voice, this, captions)
        replies.speak("Hello", rate = 0.45f)
        replies.speak("Thank you", rate = 0.45f)
        voice.onSpeakingChanged?.invoke(false)
        settle()
        assertEquals(0, captions.resumes)
        voice.finish()
        settle()
        assertEquals(1, captions.pauses)
        assertEquals(1, captions.resumes)
    }

    @Test
    fun `captions paused or resumed by hand meanwhile stay as she left them`() = runTest {
        val replies = SpokenReplies(voice, this, captions)
        replies.speak("Hello", rate = 0.45f)
        replies.userTookControl()
        voice.finish()
        settle()
        assertEquals(0, captions.resumes)
    }

    @Test
    fun `blank text, or text with no voice on the phone, is not said and leaves captions running`() = runTest {
        val replies = SpokenReplies(voice, this, captions)
        voice.hasHebrewVoice = false
        assertFalse(replies.speak("   ", rate = 0.45f))
        assertFalse(replies.speak("שתי כדורים אחרי האוכל", rate = 0.45f))
        assertEquals(emptyList(), voice.said)
        assertEquals(0, captions.pauses)
    }

    @Test
    fun `with captions off the phone just talks, and nothing turns them on afterwards`() = runTest {
        captions.isListening = false
        val replies = SpokenReplies(voice, this, captions)
        replies.speak("Hello", rate = 0.45f)
        voice.finish()
        settle()
        assertEquals(0, captions.pauses)
        assertEquals(0, captions.resumes)
    }

    @Test
    fun `a call cuts the phrase short, and captions come back when the call ends, not during it`() = runTest {
        val replies = SpokenReplies(voice, this, captions)
        replies.speak("Hello", rate = 0.45f)
        captions.duringCall = true
        replies.callChanged(began = true)
        assertFalse(voice.isBusy)
        settle()
        assertEquals(0, captions.resumes)
        captions.duringCall = false
        replies.callChanged(began = false)
        assertEquals(1, captions.resumes)
    }

    @Test
    fun `captions that come on while the phone is talking wait until it is done, unless she turned them on`() = runTest {
        captions.isListening = false
        val replies = SpokenReplies(voice, this, captions)
        replies.speak("Hello", rate = 0.45f)
        captions.isListening = true
        replies.captionsCameOn()
        assertEquals(1, captions.pauses)
        voice.finish()
        settle()
        assertEquals(1, captions.resumes)

        captions.isListening = false
        captions.isPaused = false
        replies.speak("Thank you", rate = 0.45f)
        replies.userTookControl()
        captions.isListening = true
        replies.captionsCameOn()
        assertEquals(1, captions.pauses)
    }

    @Test
    fun `the screen hears when the phone starts and stops talking`() = runTest {
        val replies = SpokenReplies(voice, this, captions)
        val heard = mutableListOf<Boolean>()
        replies.onSpeaking = { heard += it }
        replies.speak("Hello", rate = 0.45f)
        voice.finish()
        assertEquals(listOf(true, false), heard)
    }

    @Test
    fun `asking them to repeat says it in the app's language`() = runTest {
        val replies = SpokenReplies(voice, this, captions)
        Localization.withLanguage(UILanguage.English) { replies.askToRepeat(rate = 0.5f) }
        assertEquals(listOf("Sorry, could you say that again?"), voice.said)
    }
}
