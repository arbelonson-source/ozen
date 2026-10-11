package com.arbelonson.ozen

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPermission
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ProblemAudioStore
import com.arbelonson.ozen.core.SessionJournal
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun finishedLinesReachTheScreenReaderByThemselvesWhileTheCaptionsAreInFront(): Unit = runBlocking {
        val clip = File(File(context.filesDir, "device-test"), "clip.wav")
        val model = File(context.filesDir, CaptionSession.MODEL_FILE)
        assumeTrue("put clip.wav in device-test and the model at ${model.path}", clip.exists() && model.exists())
        val work = File(context.cacheDir, "caption-announce-test").apply { deleteRecursively(); mkdirs() }
        val announced = CopyOnWriteArrayList<String>()
        val readerBefore = ScreenReader.isOn
        val announceBefore = ScreenReader.announce
        ScreenReader.isOn = { true }
        ScreenReader.announce = { _, text -> announced += text }
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
            ).also { it.appIsActive = true }
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val started = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - started < 180_000 && announced.isEmpty()) {
                delay(500)
                if (CaptionState.screen.value.phase is PipelinePhase.Failed) break
            }
            assertTrue("nothing was announced: ${CaptionState.screen.value.phase}", announced.isNotEmpty())
            val finished = withContext(Dispatchers.Main) { session.pipeline.segments.filter { it.isCommitted }.map { it.text.trim() } }
            for (part in announced.flatMap { it.split("\n") }) {
                assertTrue("announced \"$part\", but the finished lines are $finished", finished.any { part.endsWith(it) })
            }
        } finally {
            withContext(Dispatchers.Main) { session.close() }
            ScreenReader.isOn = readerBefore
            ScreenReader.announce = announceBefore
        }
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
                modelFolder = File(work, "no-models"),
                historyFolder = File(work, "history"),
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

    @Test
    fun aMarkedProblemKeepsTheLastSoundOnlyWhileConversationsAreSavedAndDeleteAllTakesItToo() = runBlocking {
        val model = File(context.filesDir, CaptionSession.MODEL_FILE)
        assumeTrue("put the model at ${model.path}", model.exists())
        val work = File(context.cacheDir, "problem-sound-test").apply { deleteRecursively(); mkdirs() }
        val clips = ProblemAudioStore(File(work, "problem-audio"))
        val journal = File(work, "journal.log")
        val tone = FloatArray(32_000) { (0.1 * sin(it * 2 * PI * 440 / 16_000)).toFloat() }
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(tone),
                historyFolder = File(work, "history"),
                journalFile = journal,
                problemAudioFolder = clips.directory,
            )
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val heard = withTimeoutOrNull(120_000) {
                while (withContext(Dispatchers.Main) { session.pipeline.recentAudioSamples.isEmpty() }) delay(200)
                true
            }
            assertNotNull("no sound reached the pipeline: ${CaptionState.screen.value.phase}", heard)
            assertTrue("the sound was not kept", withContext(Dispatchers.Main) { session.markProblem() })
            val kept = clips.clips().single()
            assertTrue(SessionJournal(journal).entries().any { it.text == "  sound saved: ${kept.name}" })

            withContext(Dispatchers.Main) { session.savedConversations.deleteAll() }
            assertTrue("delete all left the sound behind", clips.clips().isEmpty())

            withContext(Dispatchers.Main) { session.savedConversations.switchSaving(false) }
            assertFalse("sound kept with saving conversations off", withContext(Dispatchers.Main) { session.markProblem() })
            assertTrue(clips.clips().isEmpty())
        } finally {
            withContext(Dispatchers.Main) { session.close() }
        }
    }

    private val PipelinePhase.modelMissing: Boolean
        get() = this is PipelinePhase.Failed && reason.engineUnavailability?.kind == EngineUnavailability.Kind.ModelNotOnDevice

    @Test
    fun aSmokeAlarmNearThePhoneBuzzesAndTakesTheBannerEvenWhileTheCaptionsCannotRun() = runBlocking {
        val work = File(context.cacheDir, "sound-alert-test").apply { deleteRecursively(); mkdirs() }
        val rate = 16_000
        val beep = FloatArray(rate / 2) { (0.3 * sin(2 * PI * 3_100.0 * it / rate)).toFloat() }
        val beeps = (beep + FloatArray(rate / 2)).let { it + it + it } + FloatArray(rate)
        val audio = ClipAudio(beeps + beeps + beeps + beeps)
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.engine = TranscriptionEngineKind.HomeServer
            it.homeServerAddress = "127.0.0.1:9"
        }
        val code = HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()).apply { save("code-for-the-test") }
        val buzzed = CopyOnWriteArrayList<AlertVibration>()
        val before = Buzz.play
        Buzz.play = { _, vibration -> buzzed += vibration }
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(context, settings, code, CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), audio, modelFolder = File(work, "no-model"), historyFolder = File(work, "history"))
                .also { it.appIsActive = true }
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val started = SystemClock.elapsedRealtime()
            var firstBanner: Long? = null
            var soundsOnly = false
            while (CaptionState.screen.value.sound?.event?.identifier != "smoke_detector" && SystemClock.elapsedRealtime() - started < 60_000) {
                soundsOnly = soundsOnly || withContext(Dispatchers.Main) { session.pipeline.isListeningForSoundsOnly }
                if (firstBanner == null && CaptionState.screen.value.sound != null) firstBanner = SystemClock.elapsedRealtime()
                delay(100)
            }
            val named = SystemClock.elapsedRealtime()
            assertEquals("smoke_detector", CaptionState.screen.value.sound?.event?.identifier)
            assertTrue("the captions ran: ${CaptionState.screen.value.phase}", soundsOnly)
            assertTrue("named ${named - (firstBanner ?: named)} ms after the first banner", named - (firstBanner ?: named) < 10_000)
            assertTrue("buzzed $buzzed", AlertVibration.pattern(SoundEvent.Importance.Critical) in buzzed)
        } finally {
            Buzz.play = before
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
        }
    }

    @Test
    fun aSoundMutedWhileListeningStaysQuietAndAlertsAgainOnceSwitchedBackOn() = runBlocking {
        val work = File(context.cacheDir, "sound-mute-test").apply { deleteRecursively(); mkdirs() }
        val rate = 16_000
        val beep = FloatArray(rate / 2) { (0.3 * sin(2 * PI * 3_100.0 * it / rate)).toFloat() }
        val alarm = (beep + FloatArray(rate / 2)).let { it + it + it } + FloatArray(rate)
        val audio = ClipAudio(FloatArray(6 * rate) + alarm + alarm + alarm + alarm + FloatArray(14 * rate) + alarm + alarm + alarm + alarm)
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.engine = TranscriptionEngineKind.HomeServer
            it.homeServerAddress = "127.0.0.1:9"
        }
        val code = HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()).apply { save("code-for-the-test") }
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(context, settings, code, CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), audio, modelFolder = File(work, "no-model"), historyFolder = File(work, "history"))
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val started = SystemClock.elapsedRealtime()
            val soundsOnly = suspend { withContext(Dispatchers.Main) { session.pipeline.isListeningForSoundsOnly } }
            while (!soundsOnly() && SystemClock.elapsedRealtime() - started < 30_000) delay(100)
            assertTrue("never listened for sounds alone: ${CaptionState.screen.value.phase}", soundsOnly())
            val sounds = settings.current.value.soundAlerts
            withContext(Dispatchers.Main) { session.setSoundAlerts(sounds.withMuted("smoke_detector", true)) }
            val muted = SystemClock.elapsedRealtime()
            val heardWhileMuted = mutableSetOf<String>()
            while (SystemClock.elapsedRealtime() - muted < 26_000) {
                CaptionState.screen.value.sound?.let { heardWhileMuted += it.event.identifier }
                delay(100)
            }
            assertFalse("a muted smoke alarm alerted", "smoke_detector" in heardWhileMuted)
            assertTrue("the alarm's beeps went unheard while muted: $heardWhileMuted", "beep" in heardWhileMuted)

            withContext(Dispatchers.Main) { session.setSoundAlerts(sounds) }
            val unmuted = SystemClock.elapsedRealtime()
            while (CaptionState.screen.value.sound?.event?.identifier != "smoke_detector" && SystemClock.elapsedRealtime() - unmuted < 40_000) delay(100)
            assertEquals("smoke_detector", CaptionState.screen.value.sound?.event?.identifier)
        } finally {
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
        }
    }

    @Test
    fun theCaptionsVoicePrintsKeepTwoRecordingsOfOneVoiceTogetherAndAnotherVoiceApart() = runBlocking {
        val work = File(context.cacheDir, "voice-print-test").apply { deleteRecursively(); mkdirs() }
        val session = withContext(Dispatchers.Main) {
            CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(0)),
                modelFolder = File(work, "no-model"),
                historyFolder = File(work, "history"),
            )
        }
        try {
            val (first, second, other) = listOf("speakerA_clip1", "speakerA_clip2", "speakerB_clip1").map { name ->
                val print = session.pipeline.embeddingInBackground(DeviceClips.asset("$name.wav"))
                assertNotNull("no voice print for $name", print)
                print!!
            }
            assertEquals(512, first.size)
            val threshold = AppSettings.default.speakerSimilarityThreshold
            assertTrue("one voice scored ${DeviceClips.cosine(first, second)}", DeviceClips.cosine(first, second) >= threshold)
            assertTrue("another voice scored ${DeviceClips.cosine(first, other)}", DeviceClips.cosine(first, other) < threshold)
        } finally {
            withContext(Dispatchers.Main) { session.close() }
        }
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
