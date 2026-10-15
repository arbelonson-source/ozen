package com.arbelonson.ozen

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
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
import com.arbelonson.ozen.core.BackupModelStatus
import com.arbelonson.ozen.core.BatteryWarning
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.OnDeviceWhisperEngine
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ProblemAudioStore
import com.arbelonson.ozen.core.ReleaseFileFetching
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.SavedSpeaker
import com.arbelonson.ozen.core.SessionJournal
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.StoppedCaptionsNotice
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.WhisperModelCatalog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun aConversationCutOffMinutesBeforeTheAppOpenedIsOfferedUntilTakenAwayAndOneFromHoursAgoIsNot() = runBlocking {
        val work = File(context.cacheDir, "recent-conversation-test").apply { deleteRecursively(); mkdirs() }
        val history = TranscriptHistoryStore(File(work, "history"))
        val now = System.currentTimeMillis() / 1_000.0
        fun saved(text: String, endedAt: Double) = TranscriptSessionRecord(
            startedAt = endedAt - 60,
            endedAt = endedAt,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = null,
            inputName = null,
            segments = listOf(SavedSegment(UUID.randomUUID(), text, null, null, endedAt - 60, true)),
        ).also { history.save(it) }
        saved("From this morning", now - 3 * 3_600)
        val cutOff = saved("The doctor said twice a day", now - 120)
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(context, SettingsHolder(SettingsStore(File(work, "settings.json"))), HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()), CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), ClipAudio(FloatArray(0)), modelFolder = File(work, "no-model"), historyFolder = File(work, "history"))
        }
        try {
            val offered = withTimeoutOrNull(5_000) { CaptionState.screen.first { it.recentConversation != null } }?.recentConversation
            assertEquals(cutOff.id, offered?.id)
            withContext(Dispatchers.Main) { session.dismissRecentConversation() }
            assertNull(CaptionState.screen.value.recentConversation)

            withContext(Dispatchers.Main) { session.loadRecentConversation() }
            assertEquals(cutOff.id, withTimeoutOrNull(5_000) { CaptionState.screen.first { it.recentConversation != null } }?.recentConversation?.id)
            withContext(Dispatchers.Main) { session.savedConversations.delete(cutOff.id) }
            assertNull(CaptionState.screen.value.recentConversation)

            val another = saved("The bus leaves at four", now - 60)
            withContext(Dispatchers.Main) { session.loadRecentConversation() }
            assertEquals(another.id, withTimeoutOrNull(5_000) { CaptionState.screen.first { it.recentConversation != null } }?.recentConversation?.id)
            withContext(Dispatchers.Main) {
                session.loadRecentConversation()
                session.dismissRecentConversation()
            }
            delay(1_000)
            assertNull("taken away while it was being looked for", CaptionState.screen.value.recentConversation)
            withContext(Dispatchers.Main) { session.loadRecentConversation() }
            assertEquals(another.id, withTimeoutOrNull(5_000) { CaptionState.screen.first { it.recentConversation != null } }?.recentConversation?.id)
            withContext(Dispatchers.Main) { session.clearCaptions() }
            assertNull(CaptionState.screen.value.recentConversation)
        } finally {
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
        }
    }

    @Test
    fun aBatteryRunningLowWhileTheMicrophoneListensPutsUpTheWarningAndChargingTakesItDown() = runBlocking {
        val work = File(context.cacheDir, "battery-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.engine = TranscriptionEngineKind.HomeServer
            it.homeServerAddress = "127.0.0.1:9"
        }
        val code = HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()).apply { save("code-for-the-test") }
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        val buzzed = CopyOnWriteArrayList<AlertVibration>()
        val before = Buzz.play
        Buzz.play = { _, vibration, _ -> buzzed += vibration }
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(context, settings, code, CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), ClipAudio(FloatArray(16_000 * 120)), modelFolder = File(work, "no-model"), historyFolder = File(work, "history"))
                .also { it.appIsActive = true }
        }
        try {
            shell("dumpsys battery unplug")
            shell("dumpsys battery set level 15")
            delay(1_000)
            assertNull("warned before anything listened", CaptionState.screen.value.battery)
            withContext(Dispatchers.Main) { session.listen() }
            val listening = withTimeoutOrNull(60_000) {
                while (!withContext(Dispatchers.Main) { session.pipeline.isListeningForSoundsOnly }) delay(100)
                true
            }
            assertNotNull("never listened for sounds alone: ${CaptionState.screen.value.phase}", listening)
            shell("dumpsys battery set level 14")
            val warned = withTimeoutOrNull(10_000) { CaptionState.screen.first { it.battery != null } }?.battery
            assertTrue("warned $warned", warned is BatteryWarning.Low && warned.percent in 14..15)
            assertEquals("buzzed $buzzed", listOf(CaptionSession.BATTERY_BUZZ), buzzed.filter { it == CaptionSession.BATTERY_BUZZ })
            delay(12_000)
            assertEquals("the warning went while the captions kept retrying: ${CaptionState.screen.value.phase}", warned, CaptionState.screen.value.battery)
            shell("dumpsys battery set ac 1")
            assertNotNull("charging left the warning up", withTimeoutOrNull(10_000) { CaptionState.screen.first { it.battery == null } })
        } finally {
            Buzz.play = before
            shell("dumpsys battery reset")
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
        }
    }

    @Test
    fun theHomeComputersBackupDownloadsToThePhoneSaysHowFarItGotPicksUpAfterAFailureAndThenIsReady(): Unit = runBlocking {
        val work = File(context.cacheDir, "backup-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.engine = TranscriptionEngineKind.HomeServer
            it.homeServerAddress = "127.0.0.1:9"
            it.spokenLanguage = UILanguage.English
            it.allowCellularModelDownload = true
        }
        val source = WhisperModelCatalog.phoneModelSource(UILanguage.English)!!
        val half = source.bytes / 2
        val halfway = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val starts = CopyOnWriteArrayList<Long>()
        val fetcher = object : ReleaseFileFetching {
            override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
                starts += from
                if (from == 0L) {
                    RandomAccessFile(appendingTo, "rw").use { it.setLength(half) }
                    received(half)
                    throw IOException("the connection dropped halfway")
                }
                halfway.countDown()
                goOn.await(30, TimeUnit.SECONDS)
                RandomAccessFile(appendingTo, "rw").use { it.setLength(source.bytes) }
                received(source.bytes - from)
            }

            override fun sha256(file: File): String = source.sha256
        }
        val models = File(work, "models")
        val session = withContext(Dispatchers.Main) {
            CaptionSession(context, settings, HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()), CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), ClipAudio(FloatArray(0)), modelFolder = models, historyFolder = File(work, "history"))
                .also { it.modelFetcher = fetcher }
        }
        try {
            withContext(Dispatchers.Main) { session.refreshBackup() }
            assertEquals(BackupModelStatus.Missing(575), session.backupStatus.value)

            withContext(Dispatchers.Main) { session.downloadBackup() }
            assertNotNull("never failed: ${session.backupStatus.value}", withTimeoutOrNull(10_000) { session.backupStatus.first { it == BackupModelStatus.Failed } })

            withContext(Dispatchers.Main) { session.downloadBackup() }
            assertTrue("the second try never started", halfway.await(10, TimeUnit.SECONDS))
            val shown = withTimeoutOrNull(5_000) { session.backupStatus.first { it == BackupModelStatus.Downloading(0.49) } }
            assertNotNull("never showed how far the first try got: ${session.backupStatus.value}", shown)
            goOn.countDown()
            assertNotNull("never ready: ${session.backupStatus.value}", withTimeoutOrNull(10_000) { session.backupStatus.first { it == BackupModelStatus.Ready } })
            assertEquals(listOf(0L, half), starts.toList())
            assertTrue(File(models, WhisperModelCatalog.phoneModelFile(UILanguage.English)).isFile)

            settings.change { it.engine = TranscriptionEngineKind.WhisperKit }
            withContext(Dispatchers.Main) { session.refreshBackup() }
            assertEquals(BackupModelStatus.NotNeeded, session.backupStatus.value)
        } finally {
            goOn.countDown()
            withContext(Dispatchers.Main) { session.close() }
            work.deleteRecursively()
        }
    }

    @Test
    fun theBackupDownloadStopsWhenWiFiGoesWithCellularDownloadsOffAndIsNotCalledFailed(): Unit = runBlocking {
        val work = File(context.cacheDir, "backup-wifi-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.engine = TranscriptionEngineKind.HomeServer
            it.homeServerAddress = "127.0.0.1:9"
            it.spokenLanguage = UILanguage.English
            it.allowCellularModelDownload = false
        }
        val source = WhisperModelCatalog.phoneModelSource(UILanguage.English)!!
        val tenth = source.bytes / 10
        val network = AtomicReference(NetworkConditions.wifi)
        val served = AtomicLong()
        val fetcher = object : ReleaseFileFetching {
            override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
                var at = from
                while (at < source.bytes) {
                    val next = minOf(at + tenth, source.bytes)
                    RandomAccessFile(appendingTo, "rw").use { it.setLength(next) }
                    served.set(next)
                    network.set(NetworkConditions.cellular)
                    received(next - at)
                    at = next
                }
            }

            override fun sha256(file: File): String = source.sha256
        }
        val models = File(work, "models")
        val session = withContext(Dispatchers.Main) {
            CaptionSession(context, settings, HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()), CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), ClipAudio(FloatArray(0)), modelFolder = models, historyFolder = File(work, "history"))
                .also {
                    it.modelFetcher = fetcher
                    it.networkNow = { network.get() }
                }
        }
        try {
            withContext(Dispatchers.Main) {
                session.refreshBackup()
                session.downloadBackup()
            }
            assertNotNull("never ended: ${session.backupStatus.value}", withTimeoutOrNull(10_000) { session.backupStatus.first { it !is BackupModelStatus.Downloading } })
            assertTrue("went on over cellular data: ${served.get()} of ${source.bytes}", served.get() < source.bytes)
            assertFalse(File(models, WhisperModelCatalog.phoneModelFile(UILanguage.English)).exists())
            assertNotEquals(BackupModelStatus.Failed, session.backupStatus.value)
        } finally {
            withContext(Dispatchers.Main) { session.close() }
            work.deleteRecursively()
        }
    }

    @Test
    fun aHomeComputerThatCannotBeReachedWithNoBackupOnThePhoneOffersTheBackupOnTheStatus(): Unit = runBlocking {
        val work = File(context.cacheDir, "backup-offer-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.engine = TranscriptionEngineKind.HomeServer
            it.homeServerAddress = "127.0.0.1:9"
            it.spokenLanguage = UILanguage.English
            it.allowCellularModelDownload = true
        }
        val code = HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()).apply { save("code-for-the-test") }
        val languageBefore = Localization.language
        Localization.language = UILanguage.English
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(context, settings, code, CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), ClipAudio(FloatArray(16_000 * 120)), modelFolder = File(work, "no-model"), historyFolder = File(work, "history"))
                .also { it.appIsActive = true }
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val offered = withTimeoutOrNull(60_000) { CaptionState.screen.first { it.status.action == PhasePresentation.Action.OpenBackupSettings } }
            assertNotNull("never offered: ${CaptionState.screen.value.phase}, ${CaptionState.screen.value.status.detail}", offered)
            assertTrue("${offered!!.status.detail}", offered.status.detail!!.endsWith("Tap to add a backup on the phone"))
        } finally {
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
            Localization.language = languageBefore
            work.deleteRecursively()
        }
    }

    @Test
    fun aHomeComputerThatCannotBeReachedIsCoveredByThePhonesOwnModelAndTheStatusSaysSo(): Unit = runBlocking {
        val model = File(context.filesDir, CaptionSession.MODEL_FILE)
        assumeTrue("put the model at ${model.path}", model.exists())
        val work = File(context.cacheDir, "cover-status-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.engine = TranscriptionEngineKind.HomeServer
            it.homeServerAddress = "127.0.0.1:9"
            it.spokenLanguage = UILanguage.Hebrew
        }
        val code = HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()).apply { save("code-for-the-test") }
        val languageBefore = Localization.language
        Localization.language = UILanguage.English
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(context, settings, code, CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()), ClipAudio(FloatArray(16_000 * 120)), historyFolder = File(work, "history"))
                .also { it.appIsActive = true }
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val covering = withTimeoutOrNull(180_000) {
                CaptionState.screen.first { it.status.detail == "Can’t reach the home computer, carrying on with the phone’s own · Tap to pause" }
            }
            assertNotNull("never said the phone covers: ${CaptionState.screen.value.phase}, ${CaptionState.screen.value.status.detail}", covering)
        } finally {
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
            Localization.language = languageBefore
            work.deleteRecursively()
        }
    }

    @Test
    fun captionsThatStopByThemselvesAndComeBackAreSaidToTheScreenReaderOnceEach(): Unit = runBlocking {
        val model = File(context.filesDir, CaptionSession.MODEL_FILE)
        assumeTrue("put the model at ${model.path}", model.exists())
        val work = File(context.cacheDir, "stop-announce-test").apply { deleteRecursively(); mkdirs() }
        val announced = CopyOnWriteArrayList<String>()
        val readerBefore = ScreenReader.isOn
        val announceBefore = ScreenReader.announce
        // Run by itself, no screen has set the app's language yet.
        val languageBefore = Localization.language
        Localization.language = UILanguage.English
        ScreenReader.isOn = { true }
        ScreenReader.announce = { _, text -> announced += text }
        val clip = ClipAudio(FloatArray(16_000 * 120))
        val busyAtFirst = object : AudioCapturing by clip {
            var tries = 0

            override suspend fun prepareSession(preferredInputUID: String?) {
                tries += 1
                if (tries == 1) error("the microphone is busy")
                clip.prepareSession(preferredInputUID)
            }
        }
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                busyAtFirst,
                historyFolder = File(work, "history"),
            ).also { it.appIsActive = true }
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val back = withTimeoutOrNull(180_000) {
                while ("Captions are back" !in announced) delay(200)
                true
            }
            assertNotNull("never said they were back: $announced, ${CaptionState.screen.value.phase}", back)
            assertEquals("said $announced", 2, announced.size)
            assertTrue("said $announced", announced[0].startsWith("Captions stopped: ") && announced[0].removePrefix("Captions stopped: ").isNotBlank())
            assertEquals("Captions are back", announced[1])
        } finally {
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
            ScreenReader.isOn = readerBefore
            ScreenReader.announce = announceBefore
            Localization.language = languageBefore
        }
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

    @Test
    fun captionsThatStopForGoodWhileTheAppIsAwaySayItOnceInANoticeThatOpeningTheAppTakesAway(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notices = { manager.activeNotifications.filter { it.tag == StoppedCaptionsNotice.IDENTIFIER } }
        manager.cancel(StoppedCaptionsNotice.IDENTIFIER, 0)
        val work = File(context.cacheDir, "stopped-notice-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        // Stopped for good, with nothing the app retries by itself: cloud
        // captions with no key. (A phone without its model used to be the
        // case here; its download is now retried.)
        settings.change {
            it.notifyWhenInBackground = true
            it.engine = TranscriptionEngineKind.Cloud
        }
        val languageBefore = Localization.language
        Localization.language = UILanguage.English
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                settings,
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(16_000)),
                modelFolder = File(work, "no-models"),
                historyFolder = File(work, "history"),
            )
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            assertNotNull(withTimeoutOrNull(30_000) { CaptionState.screen.first { it.phase.failure?.engineUnavailability?.kind == EngineUnavailability.Kind.CloudKeyNeeded } })
            assertNotNull("never posted", withTimeoutOrNull(5_000) { while (notices().isEmpty()) delay(100) })
            assertEquals("Captions stopped", notices().single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue("not tried again on coming back", withContext(Dispatchers.Main) { session.retriesOnReturn })

            withContext(Dispatchers.Main) { session.appIsActive = true }
            assertNotNull("never taken away", withTimeoutOrNull(5_000) { while (notices().isNotEmpty()) delay(100) })
            withContext(Dispatchers.Main) { session.appIsActive = false }
            delay(1_000)
            assertTrue("posted again after she saw it", notices().isEmpty())
        } finally {
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
            manager.cancel(StoppedCaptionsNotice.IDENTIFIER, 0)
            Localization.language = languageBefore
            work.deleteRecursively()
        }
    }

    @Test
    fun comingBackToTheAppDeletesConversationsPastTheirKeepingTimeEvenWithSavingOff(): Unit = runBlocking {
        val work = File(context.cacheDir, "return-sweep-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.saveHistory = false
            it.historyRetention = HistoryRetention.Week
        }
        val history = TranscriptHistoryStore(File(work, "history"))
        val now = System.currentTimeMillis() / 1_000.0
        val conversation = { at: Double ->
            val record = TranscriptSessionRecord(
                startedAt = at,
                endedAt = at + 60,
                engine = TranscriptionEngineKind.WhisperKit,
                modelVariant = null,
                inputName = null,
                segments = listOf(SavedSegment(UUID.randomUUID(), "Said at ${at.toLong()}", null, null, at, true)),
            )
            assertTrue(history.save(record))
            record.id
        }
        val old = conversation(now - 30 * 86_400)
        val recent = conversation(now - 86_400)
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                settings,
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(16_000)),
                modelFolder = File(work, "no-models"),
                historyFolder = File(work, "history"),
            )
        }
        try {
            delay(1_000)
            assertEquals("deleted before she came back", setOf(old, recent), history.listSummaries().map { it.id }.toSet())
            withContext(Dispatchers.Main) { session.appIsActive = true }
            assertNotNull("the month-old conversation stayed", withTimeoutOrNull(5_000) { while (history.listSummaries().any { it.id == old }) delay(100); true })
            assertEquals(listOf(recent), history.listSummaries().map { it.id })
        } finally {
            withContext(Dispatchers.Main) { session.close() }
            work.deleteRecursively()
        }
    }

    @Test
    fun leavingTheAppWritesTheLinesSoFarStraightAwayNotAtTheNextAutosave() = runBlocking {
        val folder = File(context.filesDir, "device-test")
        val clip = File(folder, "clip.wav")
        val model = File(context.filesDir, CaptionSession.MODEL_FILE)
        assumeTrue("put clip.wav in ${folder.path} and the model at ${model.path}", clip.exists() && model.exists())
        val work = File(context.cacheDir, "leave-save-test").apply { deleteRecursively(); mkdirs() }
        val history = TranscriptHistoryStore(File(work, "history"))
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(DeviceClips.readWav(clip)),
                historyFolder = File(work, "history"),
            )
        }
        try {
            withContext(Dispatchers.Main) {
                session.appIsActive = true
                session.listen()
            }
            val finished = withTimeoutOrNull(300_000) { CaptionState.screen.first { screen -> screen.lines.any { it.isFinal } } }
            assertNotNull("no line was finished: ${CaptionState.screen.value.phase}", finished)
            val (said, kept) = withContext(Dispatchers.Main) {
                val said = session.pipeline.segments.filter { it.isCommitted }.map { it.text }
                session.appIsActive = false
                said to history.listSummaries().singleOrNull()?.let { history.load(it.id) }
            }
            assertNotNull("nothing written on leaving the app", kept)
            val keptLines = kept!!.segments.map { it.text }
            assertTrue("said $said, kept $keptLines", keptLines.containsAll(said))
            assertNull("captions are still running, so the conversation has no end", kept.endedAt)
        } finally {
            withContext(Dispatchers.Main) { session.close() }
            work.deleteRecursively()
        }
    }

    @Test
    fun aPhoneTooFullForItsModelDownloadsItOnComingBackOnceThereIsRoomAndNotBefore(): Unit = runBlocking {
        val work = File(context.cacheDir, "full-phone-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.spokenLanguage = UILanguage.English
            it.allowCellularModelDownload = true
        }
        val free = AtomicLong(1_000_000)
        val fetches = CopyOnWriteArrayList<Long>()
        val phases = CopyOnWriteArrayList<PipelinePhase>()
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                settings,
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(16_000)),
                modelFolder = File(work, "no-models"),
                historyFolder = File(work, "history"),
            ).apply {
                freeSpace = { free.get() }
                modelFetcher = object : ReleaseFileFetching {
                    override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
                        fetches += from
                        throw IOException("the test stops the download here")
                    }

                    override fun sha256(file: File): String = ""
                }
                val shown = pipeline.onPhaseChange
                pipeline.onPhaseChange = { phases += it; shown?.invoke(it) }
            }
        }
        val tooFull = { phase: PipelinePhase -> phase is PipelinePhase.Failed && phase.reason.engineUnavailability?.kind == EngineUnavailability.Kind.NotEnoughStorage }
        try {
            assertFalse(withContext(Dispatchers.Main) { session.retriesOnReturn })
            withContext(Dispatchers.Main) { session.listen() }
            assertNotNull("never stopped for want of room: ${CaptionState.screen.value.phase}", withTimeoutOrNull(30_000) { CaptionState.screen.first { tooFull(it.phase) } })
            assertFalse("a full phone retried the way other stops do", withContext(Dispatchers.Main) { session.retriesOnReturn })
            phases.clear()
            withContext(Dispatchers.Main) { session.appIsActive = true }
            delay(2_000)
            assertTrue("tried again with the phone still full: $phases", phases.isEmpty())
            assertTrue(fetches.isEmpty())

            withContext(Dispatchers.Main) { session.appIsActive = false }
            free.set(Long.MAX_VALUE / 4)
            withContext(Dispatchers.Main) { session.appIsActive = true }
            assertNotNull("the download did not start once there was room: $phases", withTimeoutOrNull(30_000) { while (fetches.isEmpty()) delay(100); true })
        } finally {
            withContext(Dispatchers.Main) { session.close() }
            work.deleteRecursively()
        }
    }

    @Test
    fun theBackupRowLooksAgainForRoomAsTheAppComesBack(): Unit = runBlocking {
        val work = File(context.cacheDir, "backup-room-test").apply { deleteRecursively(); mkdirs() }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        settings.change {
            it.engine = TranscriptionEngineKind.HomeServer
            it.homeServerAddress = "127.0.0.1:9"
            it.spokenLanguage = UILanguage.English
            it.allowCellularModelDownload = true
        }
        val free = AtomicLong(1_000_000)
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                settings,
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(16_000)),
                modelFolder = File(work, "models"),
                historyFolder = File(work, "history"),
            ).apply { freeSpace = { free.get() } }
        }
        try {
            withContext(Dispatchers.Main) {
                session.appIsActive = true
                session.refreshBackup()
            }
            assertTrue("${session.backupStatus.value}", session.backupStatus.value is BackupModelStatus.NotEnoughRoom)
            free.set(Long.MAX_VALUE / 4)
            withContext(Dispatchers.Main) {
                session.appIsActive = false
                session.appIsActive = true
            }
            assertTrue("still short of room after she made some: ${session.backupStatus.value}", session.backupStatus.value is BackupModelStatus.Missing)
        } finally {
            withContext(Dispatchers.Main) { session.close() }
            work.deleteRecursively()
        }
    }

    @Test
    fun theJournalNotesWhenThePhoneGetsHotOrSavesBatteryAsTheIPhoneDoes(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        val work = File(context.cacheDir, "heat-power-test").apply { deleteRecursively(); mkdirs() }
        val journal = File(work, "journal.log")
        val noted = { start: String -> SessionJournal(journal).entries().map { it.text }.filter { it.startsWith(start) } }
        val session = withContext(Dispatchers.Main) {
            CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(16_000)),
                modelFolder = File(work, "no-models"),
                historyFolder = File(work, "history"),
                journalFile = journal,
            )
        }
        try {
            delay(1_000)
            assertEquals("noted before anything changed", emptyList<String>(), noted("heat:") + noted("power:"))
            shell("cmd thermalservice override-status 3")
            assertNotNull("the heat never noted", withTimeoutOrNull(5_000) { while (noted("heat:").isEmpty()) delay(100); true })
            assertTrue(noted("heat:").toString(), noted("heat:").single().contains("thermal 3 "))
            saveBattery(shell)
            assertNotNull("battery saver never noted", withTimeoutOrNull(5_000) { while (noted("power:").isEmpty()) delay(100); true })
            assertTrue(noted("power:").toString(), noted("power:").last().contains("low power true"))
        } finally {
            withContext(Dispatchers.Main) { session.close() }
            shell("cmd power set-mode 0")
            shell("cmd thermalservice reset")
            shell("dumpsys battery reset")
            work.deleteRecursively()
        }
    }

    /**
     * Battery saver on, as she would switch it. Asked right after the
     * battery is set unplugged, Android still counted the emulator as
     * charging and refused it about one time in two, so it is asked again
     * until it is on.
     */
    private suspend fun saveBattery(shell: (String) -> ByteArray) {
        val power = context.getSystemService(PowerManager::class.java)
        shell("dumpsys battery unplug")
        withTimeoutOrNull(5_000) {
            while (!power.isPowerSaveMode) {
                shell("cmd power set-mode 1")
                delay(200)
            }
        }
    }

    @Test
    fun thePhonesModelSlowsItsLivePassesWhenThePhoneGetsHotOrSavesBatteryAsTheIPhoneDoes(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val shell = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        val work = File(context.cacheDir, "heat-pace-test").apply { deleteRecursively(); mkdirs() }
        val session = withContext(Dispatchers.Main) {
            CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(16_000)),
                modelFolder = File(work, "no-models"),
                historyFolder = File(work, "history"),
                journalFile = File(work, "journal.log"),
            )
        }
        val onPhone = AppSettings.default.copy().apply { engine = TranscriptionEngineKind.WhisperKit }
        val pace = { (session.engine(onPhone) as OnDeviceWhisperEngine).secondsBetweenLivePasses(null) }
        suspend fun settles(seconds: Double) = withTimeoutOrNull(5_000) { while (pace() != seconds) delay(100); true }
        try {
            shell("cmd thermalservice reset")
            shell("cmd power set-mode 0")
            assertNotNull("a cool phone not at the usual pace: ${pace()}", settles(0.6))
            shell("cmd thermalservice override-status 3")
            assertNotNull("a hot phone kept the pace: ${pace()}", settles(1.5))
            shell("cmd thermalservice reset")
            assertNotNull("never back to the usual pace: ${pace()}", settles(0.6))
            saveBattery(shell)
            assertNotNull("battery saver kept the pace: ${pace()}", settles(1.2))
        } finally {
            withContext(Dispatchers.Main) { session.close() }
            shell("cmd power set-mode 0")
            shell("cmd thermalservice reset")
            shell("dumpsys battery reset")
            work.deleteRecursively()
        }
    }

    @Test
    fun aClosedSessionLetsGoOfEverythingSoItCanBeFreed(): Unit = runBlocking {
        val work = File(context.cacheDir, "closed-session-test").apply { deleteRecursively(); mkdirs() }
        val closed = withContext(Dispatchers.Main) {
            val session = CaptionSession(
                context,
                SettingsHolder(SettingsStore(File(work, "settings.json"))),
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(16_000)),
                modelFolder = File(work, "no-models"),
                historyFolder = File(work, "history"),
            )
            session.close()
            WeakReference(session)
        }
        try {
            val freed = withTimeoutOrNull(15_000) {
                while (closed.get() != null) {
                    Runtime.getRuntime().gc()
                    System.runFinalization()
                    delay(200)
                }
                true
            }
            assertNotNull("a closed session was still held", freed)
        } finally {
            work.deleteRecursively()
        }
    }

    // No model on the phone: in a device test its download always fails
    // (OzenTestRunner), since every language now has one.
    private val PipelinePhase.modelMissing: Boolean
        get() = this is PipelinePhase.Failed && reason.engineUnavailability?.kind in setOf(EngineUnavailability.Kind.ModelNotOnDevice, EngineUnavailability.Kind.ModelDownloadFailed)

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
        Buzz.play = { _, vibration, _ -> buzzed += vibration }
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
            withTimeoutOrNull(5_000) { while (AlertVibration.pattern(SoundEvent.Importance.Critical) !in buzzed) delay(50) }
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
                val clip = DeviceClips.asset("$name.wav")
                val print = session.pipeline.embeddingInBackground(clip + clip + clip)
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

    @Test
    fun aVoiceNamedOnItsLineIsSavedAndShownByThatNameAndANewSpellingRenamesThePerson() = runBlocking {
        val model = File(context.filesDir, CaptionSession.MODEL_FILE)
        assumeTrue("put the model at ${model.path}", model.exists())
        val work = File(context.cacheDir, "name-speaker-test").apply { deleteRecursively(); mkdirs() }
        val pair = DeviceClips.asset("speakerA_clip1.wav") + DeviceClips.asset("speakerA_clip2.wav")
        val speech = pair + pair + pair
        val audio = ClipAudio(FloatArray(8_000) + speech + FloatArray(32_000) + speech + FloatArray(32_000))
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        val session = withContext(Dispatchers.Main) {
            CaptionState.clear()
            CaptionSession(
                context,
                settings,
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                audio,
                historyFolder = File(work, "history"),
            )
        }
        try {
            withContext(Dispatchers.Main) { session.listen() }
            val voiced = withTimeoutOrNull(180_000) {
                var found: TranscriptSegment? = null
                while (found == null) {
                    found = withContext(Dispatchers.Main) { session.pipeline.segments.firstOrNull { it.speakerClusterID != null } }
                    if (found == null) delay(500)
                }
                found
            }
            assertNotNull("no line got a voice: ${CaptionState.screen.value.phase} ${session.pipeline.segments.map { it.text }}, voices heard ${session.pipeline.speakerClusters.size}", voiced)
            withContext(Dispatchers.Main) { session.nameSpeaker(voiced!!.id, "Dana") }
            val saved = settings.current.value.speakerProfiles
            assertEquals(listOf("Dana"), saved.map { it.name })
            assertEquals(512, saved.single().embedding.size)
            assertEquals(listOf("Dana"), withContext(Dispatchers.Main) { session.savedSpeakerNames })
            assertEquals("Dana", CaptionState.screen.value.lines.single { it.id == voiced!!.id }.speaker)

            withContext(Dispatchers.Main) { session.nameSpeaker(voiced!!.id, "Dahna") }
            assertEquals(listOf("Dahna"), settings.current.value.speakerProfiles.map { it.name })
            assertEquals(saved.single().id, settings.current.value.speakerProfiles.single().id)
            assertEquals("Dahna", CaptionState.screen.value.lines.single { it.id == voiced!!.id }.speaker)
        } finally {
            withContext(Dispatchers.Main) {
                session.close()
                CaptionState.clear()
            }
        }
    }

    @Test
    fun aVoiceRecordedInSettingsIsSavedUnderItsNameAndListenedForAndOneTooShortIsNot() = runBlocking {
        val work = File(context.cacheDir, "record-speaker-test").apply { deleteRecursively(); mkdirs() }
        val first = DeviceClips.asset("speakerA_clip1.wav")
        val second = DeviceClips.asset("speakerA_clip2.wav")
        val voice = first + second + first + second
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        val session = withContext(Dispatchers.Main) {
            CaptionSession(
                context,
                settings,
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(voice),
                modelFolder = File(work, "no-model"),
                historyFolder = File(work, "history"),
            )
        }
        try {
            val progress = CopyOnWriteArrayList<Double>()
            val saved = withContext(Dispatchers.Main) { session.recordSpeaker("Dana", voice.size / 16_000.0) { progress.add(it) } }
            assertTrue("the recording was refused", saved)
            assertEquals(1.0, progress.last(), 0.0)
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
            val profile = settings.current.value.speakerProfiles.single()
            assertEquals("Dana", profile.name)
            assertEquals(512, profile.embedding.size)
            assertFalse(withContext(Dispatchers.Main) { session.needsNewRecording(SavedSpeaker.grouping(settings.current.value.speakerProfiles).single()) })
            assertTrue(withContext(Dispatchers.Main) { session.pipeline.speakerClusters.any { it.name == "Dana" } })
            val other = DeviceClips.asset("speakerB_clip1.wav")
            val otherPrint = session.pipeline.embeddingInBackground(other + other + other)!!
            val samePrint = session.pipeline.embeddingInBackground(second + second + second)!!
            val threshold = AppSettings.default.speakerSimilarityThreshold
            assertTrue("her own voice scored ${DeviceClips.cosine(profile.embedding, samePrint)}", DeviceClips.cosine(profile.embedding, samePrint) >= threshold)
            assertTrue("another voice scored ${DeviceClips.cosine(profile.embedding, otherPrint)}", DeviceClips.cosine(profile.embedding, otherPrint) < threshold)

            assertFalse(withContext(Dispatchers.Main) { session.recordSpeaker("Nobody", 1.0) {} })
            assertEquals(listOf("Dana"), settings.current.value.speakerProfiles.map { it.name })

            val stopped = launch(Dispatchers.Main) { session.recordSpeaker("Stopped", voice.size / 16_000.0) {} }
            delay(6_000)
            stopped.cancel()
            stopped.join()
            assertEquals(listOf("Dana"), settings.current.value.speakerProfiles.map { it.name })
            assertFalse(withContext(Dispatchers.Main) { session.pipeline.speakerClusters.any { it.name == "Stopped" } })
        } finally {
            withContext(Dispatchers.Main) { session.close() }
        }
    }

    @Test
    fun recordingsAddedFromFilesAreSavedUnderTheNameInEachFileNameAndOnesWithoutANameOrSpeechAreNot() = runBlocking {
        val work = File(context.cacheDir, "add-recordings-test").apply { deleteRecursively(); mkdirs() }
        val first = DeviceClips.asset("speakerA_clip1.wav")
        val second = DeviceClips.asset("speakerA_clip2.wav")
        val other = DeviceClips.asset("speakerB_clip1.wav")
        val files = listOf(
            "Savta 1.wav" to first + first + first,
            "Savta_2.wav" to second + second + second,
            "Yossi.wav" to other + other + other,
            "2.wav" to first + first + first,
            "Quiet.wav" to FloatArray(5 * 16_000),
        ).map { (name, samples) -> File(work, name).also { DeviceClips.writeWav(it, samples) } }
        val settings = SettingsHolder(SettingsStore(File(work, "settings.json")))
        val session = withContext(Dispatchers.Main) {
            CaptionSession(
                context,
                settings,
                HomeServerCodeStore(File(work, "code"), KeystoreCodeCipher()),
                CloudKeyStore(File(work, "cloud-keys"), KeystoreCodeCipher()),
                ClipAudio(FloatArray(0)),
                modelFolder = File(work, "no-model"),
                historyFolder = File(work, "history"),
            )
        }
        try {
            val result = withContext(Dispatchers.Main) { session.addRecordings(files.map { Uri.fromFile(it) }) }
            assertEquals(mapOf("Savta" to 2, "Yossi" to 1), result.added)
            assertEquals(listOf("2.wav", "Quiet.wav"), result.unusable)
            val saved = settings.current.value.speakerProfiles
            assertEquals(listOf("Savta", "Savta", "Yossi"), saved.map { it.name })
            assertTrue(saved.all { it.embedding.size == 512 })
            assertEquals(setOf("Savta", "Yossi"), withContext(Dispatchers.Main) { session.pipeline.speakerClusters.mapNotNull { it.name }.toSet() })
            val threshold = AppSettings.default.speakerSimilarityThreshold
            assertTrue("her two recordings scored ${DeviceClips.cosine(saved[0].embedding, saved[1].embedding)}", DeviceClips.cosine(saved[0].embedding, saved[1].embedding) >= threshold)
            assertTrue("another voice scored ${DeviceClips.cosine(saved[0].embedding, saved[2].embedding)}", DeviceClips.cosine(saved[0].embedding, saved[2].embedding) < threshold)
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
