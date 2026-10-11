package com.arbelonson.ozen

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPermission
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.TranscriptHistoryStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
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
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                audio,
                historyFolder = File(work, "history"),
            )
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
        withContext(Dispatchers.Main) { session.close() }
        val screen = CaptionState.screen.value
        val heard = screen.lines.filter { it.isFinal }.joinToString(" ") { it.text }
        val wrong = DeviceClips.wordErrorRate(said, heard)
        Log.i("OzenSession", "%.1f s, phase %s, %d lines, %.0f%% words wrong | %s".format(seconds, screen.phase, screen.lines.size, wrong * 100, heard))
        assertTrue("the pipeline stopped: ${screen.phase}", screen.phase !is PipelinePhase.Failed)
        assertTrue("%.0f%% of the words wrong: $heard".format(wrong * 100), wrong < 0.35)
        val history = TranscriptHistoryStore(File(work, "history"))
        val conversation = history.listSummaries().single()
        assertNotNull("captions stopped, so the conversation has an end", conversation.endedAt)
        val kept = history.load(conversation.id)!!.segments.joinToString(" ") { it.text }
        assertTrue("History kept: $kept", DeviceClips.wordErrorRate(said, kept) < 0.35)
    }

    @Test
    fun twentySecondsOfSpeechWithoutAPauseShowLiveTextBeforeTheLineIsFinished() = runBlocking {
        val clip = File(File(context.filesDir, "device-test"), "clip.wav")
        val model = File(context.filesDir, CaptionSession.MODEL_FILE)
        assumeTrue("put clip.wav in device-test and the model at ${model.path}", clip.exists() && model.exists())
        val work = File(context.cacheDir, "caption-live-test").apply { deleteRecursively(); mkdirs() }
        val speech = DeviceClips.withoutPauses(DeviceClips.readWav(clip)).copyOf(20 * 16_000)
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(speech),
                historyFolder = File(work, "history"),
            )
        }
        val started = SystemClock.elapsedRealtime()
        var liveAt: Long? = null
        var finishedAt: Long? = null
        val watcher = launch(Dispatchers.Default) {
            CaptionState.screen.collect { screen ->
                val now = SystemClock.elapsedRealtime() - started
                if (liveAt == null && screen.lines.any { !it.isFinal }) liveAt = now
                if (finishedAt == null && screen.lines.any { it.isFinal }) finishedAt = now
            }
        }
        withContext(Dispatchers.Main) { session.listen() }
        while (SystemClock.elapsedRealtime() - started < 180_000 && finishedAt == null) {
            delay(500)
            if (CaptionState.screen.value.phase is PipelinePhase.Failed) break
        }
        withContext(Dispatchers.Main) { session.close() }
        watcher.cancel()
        Log.i("OzenSession", "live text at %s ms, line finished at %s ms".format(liveAt, finishedAt))
        assertTrue("the pipeline stopped: ${CaptionState.screen.value.phase}", CaptionState.screen.value.phase !is PipelinePhase.Failed)
        val live = liveAt
        assertNotNull("no live text in 20 seconds of speech", live)
        assertTrue("live text at $live ms came after the line was finished at $finishedAt ms", live!! < (finishedAt ?: Long.MAX_VALUE))
    }

    @Test
    fun pairingWithTheHomeComputerTakesOverFromAPhoneWithoutItsModel(): Unit = runBlocking {
        val work = File(context.cacheDir, "caption-pairing-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        val codes = HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher())
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                settings,
                codes,
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(16_000)),
                File(work, "not-downloaded.bin"),
                File(work, "history"),
            )
        }
        val pairing = PairingRequests(settings, codes::save).apply { onPaired = session::settingsChanged }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val stopped = withTimeoutOrNull(30_000) { CaptionState.screen.first { it.phase.modelMissing } }
            assertNotNull("a fresh phone did not stop on its missing model: ${CaptionState.screen.value.phase}", stopped)
            withContext(Dispatchers.Main) {
                pairing.open("ozen://pair?address=ws://10.0.2.2:9&code=devicetest")
                assertTrue(pairing.acceptPending())
            }
            val tookOver = withTimeoutOrNull(30_000) {
                CaptionState.screen.first { !it.phase.modelMissing && it.phase != PipelinePhase.Idle }
            }
            assertNotNull("captions stayed stopped on the missing model after pairing", tookOver)
        } finally {
            withContext(Dispatchers.Main) { session.close() }
        }
    }

    private val PipelinePhase.modelMissing: Boolean
        get() = this is PipelinePhase.Failed && reason.engineUnavailability?.kind == EngineUnavailability.Kind.ModelNotOnDevice

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
