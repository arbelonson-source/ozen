package com.arbelonson.ozen.whisper

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.DeviceClips
import com.arbelonson.ozen.core.EngineAvailability
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.OnDeviceWhisperEngine
import com.arbelonson.ozen.core.TranscriptToken
import java.io.File
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
class OnDeviceEngineDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.filesDir, "device-test")

    @Test
    fun aRecordedLectureComesOutAsItsWordsThroughTheWholePhoneEngine() = runBlocking {
        val reference = File(folder, "clip.txt")
        assumeTrue("put clip.txt in ${folder.path}", reference.exists())
        val tokens = heard(DeviceClips.readWav(clip()))
        val text = tokens.filter { it.isFinal }.joinToString(" ") { it.text }
        val wrong = DeviceClips.wordErrorRate(reference.readText(), text)
        assertTrue("%.0f%% of the words wrong: $text".format(wrong * 100), wrong < 0.35)
    }

    @Test
    fun twentySecondsOfSpeechWithoutAPauseGetLiveWordsBeforeTheLineIsFinished() = runBlocking {
        val speech = DeviceClips.withoutPauses(DeviceClips.readWav(clip())).copyOf(20 * 16_000)
        val tokens = heard(speech)
        val beforeFinished = tokens.takeWhile { !it.isFinal }
        assertTrue("no live words before the line was finished: ${tokens.size} tokens", beforeFinished.any { it.text.isNotBlank() })
    }

    private fun clip(): File {
        val model = File(folder, "model.bin")
        val clip = File(folder, "clip.wav")
        assumeTrue("put model.bin and clip.wav in ${folder.path}", model.exists() && clip.exists())
        return clip
    }

    private suspend fun heard(audio: FloatArray): List<TranscriptToken> {
        val file = File(folder, "model.bin")
        val scorer = checkNotNull(SileroVoiceScorer.fromAssets(context)) { "the voice model did not load" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val engine = OnDeviceWhisperEngine(
            modelName = file.name,
            loader = ModelFileLoader(file, context.applicationInfo.nativeLibraryDir, threads),
            voiceScorerFactory = {
                scorer.reset()
                val score: (FloatArray) -> Float? = { input -> scorer.score(input) }
                score
            },
        )
        try {
            assertEquals(EngineAvailability.Available, engine.prepare("he") {})
            val chunks = flow {
                for (start in audio.indices step 1_600) {
                    emit(audio.copyOfRange(start, minOf(start + 1_600, audio.size)))
                    delay(100)
                }
            }
            val started = SystemClock.elapsedRealtime()
            val tokens = withTimeout(600_000) { engine.stream("he", chunks).toList() }
            val finals = tokens.count { it.isFinal }
            Log.i(
                "OzenWhisper",
                "engine: %.1f s of audio in %.1f s, %d live and %d final tokens | %s | %s".format(
                    audio.size / 16_000.0, (SystemClock.elapsedRealtime() - started) / 1000.0, tokens.size - finals, finals,
                    tokens.filter { it.isFinal }.joinToString(" ") { it.text }, engine.diagnosticsSummary(),
                ),
            )
            return tokens
        } finally {
            engine.release()
            scorer.close()
        }
    }

    @Test
    fun aModelThatIsNotOnThePhoneIsReportedAsNotOnThePhone() = runBlocking {
        val missing = File(folder, "not-downloaded.bin")
        val engine = OnDeviceWhisperEngine(missing.name, ModelFileLoader(missing, context.applicationInfo.nativeLibraryDir))
        val result = engine.prepare("he") {}
        assertEquals(
            EngineUnavailability.Kind.ModelNotOnDevice,
            (result as? EngineAvailability.Unavailable)?.why?.kind,
        )
    }
}
