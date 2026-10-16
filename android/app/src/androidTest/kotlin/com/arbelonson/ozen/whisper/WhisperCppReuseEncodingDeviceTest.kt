package com.arbelonson.ozen.whisper

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.CaptionSession
import com.arbelonson.ozen.DeviceClips
import com.arbelonson.ozen.core.WhisperPassOptions
import com.arbelonson.ozen.core.WhisperSegment as PassSegment
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperCppReuseEncodingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.filesDir, "device-test")
    private val options = WhisperPassOptions.final("he")
    private lateinit var model: WhisperModel
    private lateinit var passes: WhisperCppPasses

    @Before
    fun load() {
        val file = listOf(File(folder, "model.bin"), File(context.filesDir, CaptionSession.MODEL_FILE), File(folder, CaptionSession.MODEL_FILE))
            .firstOrNull { it.exists() }
        assumeTrue("put the speech model in ${folder.path}", file != null)
        model = checkNotNull(WhisperModel.load(file!!.path, context.applicationInfo.nativeLibraryDir)) { "the model did not load" }
        passes = WhisperCppPasses(model, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
    }

    @After
    fun close() {
        if (::model.isInitialized) model.close()
    }

    private fun clip(seconds: Double): FloatArray {
        val names = listOf("speakerA_clip1.wav", "speakerA_clip2.wav", "speakerB_clip1.wav")
        val joined = (names + names + names).map { DeviceClips.asset(it) }.reduce { all, next -> all + next }
        return joined.copyOf((seconds * 16_000).toInt())
    }

    private fun ids(segments: List<PassSegment>): List<Int> = segments.flatMap { segment -> segment.tokens.map { it.id } }

    private fun pass(audio: FloatArray, temperature: Float, reuse: Boolean): Pair<List<Int>, Double> {
        val started = SystemClock.elapsedRealtime()
        val segments = runBlocking { passes.once(audio, options, temperature, reuse) }
        return ids(segments) to (SystemClock.elapsedRealtime() - started) / 1000.0
    }

    @Test
    fun aReusedEncodingGivesTheSameTokensAsAFreshOneAtTemperatureZero() {
        val audio = clip(3.0)
        val fresh = pass(audio, 0f, false).first
        assertTrue("nothing was heard", fresh.size > 1)
        assertEquals(fresh, pass(audio, 0f, false).first)
        assertEquals(fresh, pass(audio, 0f, true).first)
        assertEquals(fresh, pass(audio, 0f, true).first)
    }

    @Test
    fun aRetryAtAHigherTemperatureOverAReusedEncodingMatchesOneOverAFreshEncoding() {
        val audio = clip(3.0)
        val fresh = pass(audio, 0.2f, false).first
        assertTrue("nothing was heard", fresh.size > 1)
        pass(audio, 0f, false)
        assertEquals(fresh, pass(audio, 0.2f, true).first)
    }

    @Test
    fun aReusedEncodingIsClearlyFasterThanAFreshOne() {
        for (seconds in listOf(3.0, 8.0)) {
            val audio = clip(seconds)
            pass(audio, 0f, false)
            val fresh = (1..2).minOf { pass(audio, 0f, false).second }
            val reused = (1..2).minOf { pass(audio, 0f, true).second }
            Log.i("OzenWhisper", "reuse timing %.0f s clip: fresh %.2f s, reused %.2f s".format(seconds, fresh, reused))
            assertTrue("%.0f s clip: reused %.2f s against fresh %.2f s".format(seconds, reused, fresh), reused < fresh * 0.6)
        }
    }

    @Test
    fun differentAudioIsEncodedAgainEvenWhenReuseIsAsked() {
        val first = clip(3.0)
        val other = first.copyOf().also { for (i in it.indices) it[i] = it[it.size - 1 - i] }
        val expected = pass(other, 0f, false).first
        pass(first, 0f, false)
        assertEquals(expected, pass(other, 0f, true).first)
        val longer = clip(4.0)
        val expectedLonger = pass(longer, 0f, false).first
        pass(first, 0f, false)
        assertEquals(expectedLonger, pass(longer, 0f, true).first)
    }

    @Test
    fun anAbortedEncodingIsNotReusedAndTheNextAttemptEncodesAgain() {
        val audio = clip(8.0)
        val (expected, freshSeconds) = pass(audio, 0f, false)
        assertTrue("nothing was heard", expected.size > 1)
        val stopping = thread {
            Thread.sleep(150)
            passes.abortRunningPass()
        }
        val started = SystemClock.elapsedRealtime()
        val stopped = runCatching { pass(audio, 0f, false) }
        val stoppedSeconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        stopping.join()
        Log.i("OzenWhisper", "aborted pass returned after %.2f s (fresh %.2f s): %s".format(stoppedSeconds, freshSeconds, stopped))
        assertTrue("the pass was not stopped: $stopped", stopped.isFailure)
        assertTrue("the stopped pass ran for %.2f s of %.2f s".format(stoppedSeconds, freshSeconds), stoppedSeconds < freshSeconds * 0.8)
        val (again, seconds) = pass(audio, 0f, true)
        assertEquals(expected, again)
        assertTrue("it did not encode again: %.2f s against fresh %.2f s".format(seconds, freshSeconds), seconds > freshSeconds * 0.6)
    }
}
