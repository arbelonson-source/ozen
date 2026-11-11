package com.arbelonson.ozen

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.EngineAvailability
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.TranscriptToken
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BuiltInSpeechEngineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // "And so, my fellow Americans, ask not what your country can do for
    // you; ask what you can do for your country." 11 s, 16 kHz, in the
    // public domain; the clip whisper.cpp ships for its own tests.
    private fun speech(): FloatArray {
        val bytes = instrumentation.context.assets.open("ask-not.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = buffer.getInt(at + 4)
            if (id == "data") {
                return FloatArray(size / 2) { buffer.getShort(at + 8 + it * 2) / 32_768f }
            }
            at += 8 + size
        }
        error("no audio in the clip")
    }

    // The clip at real time, a tenth of a second at a time, with silence
    // before and after it.
    private fun audio(samples: FloatArray, quietBefore: Int, quietAfter: Int) = flow {
        val tenth = BuiltInSpeechEngine.SAMPLE_RATE / 10
        repeat(quietBefore) {
            emit(FloatArray(tenth))
            delay(100)
        }
        for (start in samples.indices step tenth) {
            emit(samples.copyOfRange(start, minOf(start + tenth, samples.size)))
            delay(100)
        }
        repeat(quietAfter) {
            emit(FloatArray(tenth))
            delay(100)
        }
    }

    private fun assertWritten(tokens: List<TranscriptToken>) {
        val written = tokens.filter { it.isFinal }.joinToString(" ") { it.text }.lowercase()
        for (phrase in listOf("fellow americans", "ask not", "your country can do for you", "you can do for your country")) {
            assertTrue("\"$phrase\" missing from the finished lines: \"$written\"", phrase in written)
        }
        assertTrue("no line while it was said", tokens.any { !it.isFinal })
    }

    private suspend fun englishEngine(): BuiltInSpeechEngine {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val engine = BuiltInSpeechEngine(instrumentation.targetContext)
        val ready = engine.prepare("en") {}
        assumeTrue("no English on this phone's own recognizer: $ready", ready == EngineAvailability.Available)
        return engine
    }

    @Test
    fun theRecognizerOnThePhoneWritesWhatOzensOwnAudioSays() = runBlocking {
        val engine = englishEngine()
        val tokens = withTimeout(60_000) { engine.stream("en", audio(speech(), quietBefore = 0, quietAfter = 15)).toList() }
        assertWritten(tokens)
    }

    // Captions stopped in a quiet room: the recognizer answers "no match",
    // and that is the end of the captions, not an error.
    @Test
    fun captionsStoppedAfterOnlySilenceEndQuietly() = runBlocking {
        val engine = englishEngine()
        val tokens = withTimeout(60_000) { engine.stream("en", audio(FloatArray(0), quietBefore = 30, quietAfter = 0)).toList() }
        assertEquals(emptyList<TranscriptToken>(), tokens.filter { it.isFinal })
    }

    @Test
    fun aLanguageTheRecognizerDoesNotHaveOnThePhoneIsRefused() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val answer = BuiltInSpeechEngine(instrumentation.targetContext).prepare("zz") {}
        assertEquals(EngineUnavailability.Kind.LanguageNotSupportedOnDevice, answer.unavailability?.kind)
    }

    // Asking again right after an answer can reach the recognizer while it
    // still shuts down from the last question: it then answers "server
    // disconnected", or never answers at all. Both happened on the
    // emulator, and refused "Captions now" or hung. Each question must
    // still get the phone's languages, not "none".
    @Test
    fun askedOverAndOverTheRecognizerNamesItsLanguagesEveryTime() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val first = BuiltInSpeechEngine.installedLanguages(instrumentation.targetContext)
        assumeTrue("no recognition on the phone alone", first != null)
        val answers = List(ASKED_TIMES) { BuiltInSpeechEngine.installedLanguages(instrumentation.targetContext) }
        assertEquals(List(ASKED_TIMES) { first }, answers)
    }

    private companion object {
        const val ASKED_TIMES = 80
    }
}
