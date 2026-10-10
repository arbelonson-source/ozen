package com.arbelonson.ozen

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPermission
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.SettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptionSessionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRecordedLectureBecomesCaptionsOnTheScreenThroughTheWholeApp() = runBlocking {
        val folder = File(context.filesDir, "device-test")
        val clip = File(folder, "clip.wav")
        val reference = File(folder, "clip.txt")
        val model = File(context.filesDir, CaptionSession.MODEL_FILE)
        assumeTrue(
            "put clip.wav and clip.txt in ${folder.path} and the model at ${model.path}",
            clip.exists() && reference.exists() && model.exists(),
        )
        val work = File(context.cacheDir, "caption-session-test").apply { deleteRecursively(); mkdirs() }
        val audio = ClipAudio(DeviceClips.readWav(clip))
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                audio,
            )
        }
        var sawLiveLine = false
        val watcher = launch(Dispatchers.Default) {
            CaptionState.screen.collect { screen -> if (screen.lines.any { !it.isFinal }) sawLiveLine = true }
        }
        val started = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Main) { session.listen() }
        val said = reference.readText()
        while (SystemClock.elapsedRealtime() - started < 600_000) {
            delay(1_000)
            val now = CaptionState.screen.value
            if (now.phase is PipelinePhase.Failed) break
            val finished = now.lines.filter { it.isFinal }.joinToString(" ") { it.text }
            if (audio.fedAll && DeviceClips.wordErrorRate(said, finished) < 0.35) break
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        withContext(Dispatchers.Main) { session.pipeline.stop() }
        watcher.cancel()
        val screen = CaptionState.screen.value
        val heard = screen.lines.filter { it.isFinal }.joinToString(" ") { it.text }
        val wrong = DeviceClips.wordErrorRate(said, heard)
        Log.i("OzenSession", "%.1f s, phase %s, %d lines, %.0f%% words wrong | %s".format(seconds, screen.phase, screen.lines.size, wrong * 100, heard))
        assertTrue("the pipeline stopped: ${screen.phase}", screen.phase !is PipelinePhase.Failed)
        assertTrue("no live line before the lines were finished", sawLiveLine)
        assertTrue("%.0f%% of the words wrong: $heard".format(wrong * 100), wrong < 0.35)
    }

    private class ClipAudio(private val samples: FloatArray) : AudioCapturing {
        @Volatile
        var fedAll = false

        @Volatile
        private var stopped = false

        override val availableInputs = listOf(AudioInputDescriptor("builtInMic:clip", "Clip", AudioPortType.BuiltInMic))
        override val selectedInputUID: String = availableInputs.first().uid
        override val inputLevel = 0f
        override var onInputsChanged: (() -> Unit)? = null
        override var onCaptureLost: (() -> Unit)? = null

        override suspend fun requestPermission() = AudioPermission.Granted

        override suspend fun prepareSession(preferredInputUID: String?) {}

        override fun startCapture(): Flow<FloatArray> = flow {
            stopped = false
            for (start in samples.indices step 1_600) {
                if (stopped) return@flow
                emit(samples.copyOfRange(start, minOf(start + 1_600, samples.size)))
                delay(100)
            }
            fedAll = true
            while (!stopped) {
                emit(FloatArray(1_600))
                delay(100)
            }
        }

        override fun stopCapture() {
            stopped = true
        }

        override fun selectInput(uid: String) {}

        override fun refreshInputs() {}
    }
}
