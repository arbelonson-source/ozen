package com.arbelonson.ozen.whisper

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelEstimate
import com.arbelonson.ozen.core.WhisperAudioContext
import java.io.File
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSpeedDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val libraries = context.applicationInfo.nativeLibraryDir
    private val folder = File(context.filesDir, "device-test")

    @Test
    fun theMatrixTestOfWhisperCppAnswersAPositiveSpeedOnThisPhone() {
        assertTrue(WhisperCpp.loadCpu(libraries))
        val gflops = WhisperCpp.benchMatMul(PhoneSpeedMeter.engineThreads(), 600)
        Log.i("OzenWhisper", "matrix speed %.1f GFLOPS on %d threads".format(gflops, PhoneSpeedMeter.engineThreads()))
        assertTrue("speed $gflops", gflops > 0.5)
        assertTrue("speed $gflops", gflops < 100_000)
    }

    @Test
    fun theWholePhoneTestFinishesInTheTimeItPromisesAndShowsProgress() = runBlocking {
        val shown = ArrayList<Float>()
        val started = SystemClock.elapsedRealtime()
        val speed = PhoneSpeedMeter(libraries).run { shown += it }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        assertNotNull(speed)
        assertTrue(speed!!.gflops > 0.5)
        assertEquals(PhoneSpeedMeter.engineThreads(), speed.threads)
        assertEquals(PhoneSpeedMeter.ROUNDS + 1, shown.size)
        assertEquals(1f, shown.last())
        Log.i("OzenWhisper", "phone test took %.1f s, %.1f GFLOPS".format(seconds, speed.gflops))
        assertTrue("took $seconds s", seconds in 8.0..20.0)
    }

    @Test
    fun calibrationTimesARealLivePassOfEachModelFileThatIsOnThePhone() {
        val present = PhoneModelCatalog.all.filter { File(folder, it.file).isFile }
        assumeTrue("put model files named as in the catalog in ${folder.path}", present.isNotEmpty())
        val threads = PhoneSpeedMeter.engineThreads()
        assertTrue(WhisperCpp.loadCpu(libraries))
        val matrix = (1..3).map { WhisperCpp.benchMatMul(threads, 1_500) }.sorted()[1]
        val random = Random(7)
        val audio = FloatArray(6 * 16_000) { 0.02f * sin(it * 0.05f) + 0.01f * (random.nextFloat() - 0.5f) }
        val frames = WhisperAudioContext.frames(audio.size, false)
        for (model in present) {
            val handle = WhisperCpp.load(File(folder, model.file).path)
            assertTrue(model.file, handle != 0L)
            try {
                val times = (1..4).map {
                    val started = SystemClock.elapsedRealtime()
                    WhisperCpp.pass(handle, audio, "he", null, 24, 0f, threads, true, true, frames, false)
                    (SystemClock.elapsedRealtime() - started) / 1000.0
                }
                val steady = times.drop(1).sorted()[1]
                val estimate = PhoneModelEstimate.passSeconds(model, matrix)
                Log.i(
                    "OzenCalibration",
                    "%s matrix %.1f GFLOPS, passes %s s, steady %.2f s, cost %.0f GFLOP, estimate %.2f s, real share %.2f".format(
                        model.file, matrix, times.joinToString { "%.2f".format(it) }, steady, model.passGflop, estimate,
                        model.passGflop / (steady * matrix),
                    ),
                )
            } finally {
                WhisperCpp.free(handle)
            }
        }
    }
}
