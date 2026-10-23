package com.arbelonson.ozen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.FileTranscription
import com.arbelonson.ozen.core.FileTranscriptionProblem
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.TranscriptFormats
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscribeRecordingDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val app = context.applicationContext as OzenApplication
    private val transcriber get() = app.transcriber
    private val folder = File(context.cacheDir, "recording-test").apply { mkdirs() }
    private val inserted = mutableListOf<Uri>()
    private val saved = mutableListOf<UUID>()
    private val savingBefore = app.settings.current.value.saveHistory
    private val languageBefore = app.settings.current.value.languageCode
    private val conversationsBefore = app.captions.savedConversations.list().map { it.id }.toSet()

    @Before
    fun hebrewModelIsThere() {
        assumeTrue("put ${PhoneModelCatalog.OZEN_HEBREW_FILE} in ${context.filesDir}", File(context.filesDir, PhoneModelCatalog.OZEN_HEBREW_FILE).isFile)
        app.settings.change { it.saveHistory = true; it.languageCode = "he" }
    }

    @After
    fun tidy() {
        transcriber.cancel()
        waitUntilIdle()
        transcriber.close()
        for (conversation in app.captions.savedConversations.list()) {
            if (conversation.id !in conversationsBefore && (conversation.id in saved || conversation.title in sharedNames)) app.captions.savedConversations.delete(conversation.id)
        }
        app.settings.change { it.saveHistory = savingBefore; it.languageCode = languageBefore }
        for (uri in inserted) runCatching { context.contentResolver.delete(uri, null, null) }
        folder.deleteRecursively()
    }

    private val sharedNames = mutableSetOf<String>()

    private fun lecture(seconds: Int, name: String): Uri {
        val clip = File(context.filesDir, "device-test/clip.wav")
        assumeTrue("put clip.wav in ${clip.parent}", clip.isFile)
        val whole = DeviceClips.readWav(clip)
        val samples = FloatArray(seconds * 16_000) { whole[it % whole.size] }
        return shareable(name, "audio/wav") { DeviceClips.writeWav(it, samples) }
    }

    private fun shareable(name: String, type: String, write: (File) -> Unit): Uri {
        val file = File(folder, name)
        write(file)
        sharedNames += name.substringBeforeLast('.')
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, type)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/ozen-test")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)) { "the media store refused $name" }
        inserted += uri
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        return uri
    }

    private fun share(uri: Uri, type: String) = ActivityScenario.launch<MainActivity>(
        Intent(Intent.ACTION_SEND).setClassName(context, MainActivity::class.java.name).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun waitUntilIdle() {
        val until = SystemClock.elapsedRealtime() + 60_000
        while (transcriber.state.value is FileTranscription.Working && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
    }

    private fun waitFor(timeoutMillis: Long, what: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < until) { "timed out waiting for $what; state ${transcriber.state.value}" }
            Thread.sleep(200)
        }
    }

    @Test
    fun aVoiceNoteSharedToOzenIsTranscribedSavedToHistoryAndExportedAsSubtitles() {
        val uri = lecture(20, "Shared lecture note.wav")
        share(uri, "audio/wav").use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("transcribe-name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("transcribe-privacy").assertExists()
            compose.onNodeWithTag(TRANSCRIBE_START).performClick()
            waitFor(600_000, "the transcript") { transcriber.state.value is FileTranscription.Done }
            val done = transcriber.state.value as FileTranscription.Done
            assertEquals(PhoneModelCatalog.OZEN_HEBREW_FILE, done.model.file)
            assertTrue("no lines came out", done.lines.isNotEmpty())
            Log.i("OzenTranscribe", "LINES: " + done.lines.joinToString(" | ") { TranscriptFormats.clock(it.startSeconds) + " " + it.text })
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(TRANSCRIBE_LINE).fetchSemanticsNodes().isNotEmpty() }
            assertNotNull(done.recordId)
            val conversation = app.captions.savedConversations.list().first { it.id == done.recordId }
            assertEquals("Shared lecture note", conversation.title)
            val record = app.captions.savedConversations.load(done.recordId!!)!!
            assertEquals(done.lines.size, record.segments.size)
            assertEquals(done.lines.map { it.text }, record.segments.map { it.text })
            saved += done.recordId!!
            val cues = TranscriptFormats.srt(done.lines).trim().split("\n\n")
            assertEquals(done.lines.size, cues.size)
            assertTrue(cues.all { it.lines().size >= 3 && "-->" in it.lines()[1] })
            assertTrue(app.captions.savedConversations.search(done.lines.first().text.split(" ").first { it.length > 2 }).any { it.id == done.recordId })
        }
    }

    @Test
    fun cancellingStopsTheWorkQuicklyAndLeavesNothingInHistory() {
        val uri = lecture(240, "Cancel me.wav")
        val before = app.captions.savedConversations.list().size
        share(uri, "audio/wav").use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(TRANSCRIBE_START).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(TRANSCRIBE_START).performClick()
            waitFor(300_000, "work to begin") { (transcriber.state.value as? FileTranscription.Working)?.stage == FileTranscription.Working.Stage.Transcribing }
            Thread.sleep(3_000)
            compose.onNodeWithTag(TRANSCRIBE_CANCEL).performClick()
            val asked = SystemClock.elapsedRealtime()
            waitFor(15_000, "the stop") { transcriber.state.value is FileTranscription.Ready }
            Log.i("OzenTranscribe", "cancel took ${SystemClock.elapsedRealtime() - asked} ms")
            assertEquals(before, app.captions.savedConversations.list().size)
        }
    }

    @Test
    fun aFileThatIsNotAudioGetsAPlainMessageInsteadOfACrash() {
        val uri = shareable("Notes.m4a", "audio/mp4") { it.writeText("this is not a recording") }
        share(uri, "audio/mp4").use {
            waitFor(30_000, "the message") { transcriber.state.value is FileTranscription.Failed }
            assertEquals(FileTranscriptionProblem.CouldNotRead, (transcriber.state.value as FileTranscription.Failed).problem)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("transcribe-problem").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun anOggOpusVoiceNoteLikeWhatsAppSendsIsDecodedToSixteenKilohertzMono() {
        val bytes = instrumentation.context.assets.open("voice-note.ogg").use { it.readBytes() }
        val uri = shareable("PTT-20261016-WA0001.opus", "audio/ogg") { it.writeBytes(bytes) }
        val pieces = mutableListOf<FloatArray>()
        val outcome = AudioFileDecoder.decode(context, uri) { pieces += it; true }
        assertEquals(DecodeOutcome.Finished, outcome)
        val seconds = pieces.sumOf { it.size } / 16_000.0
        assertTrue("$seconds s", seconds in 1.6..2.0)
        assertTrue(pieces.any { piece -> piece.any { kotlin.math.abs(it) > 0.01f } })
    }

    @Test
    fun aLongRecordingIsReadAboutASecondAtATimeNeverWhole() {
        val minutes = 8
        val uri = shareable("Long lecture.wav", "audio/wav") { DeviceClips.writeWav(it, FloatArray(minutes * 60 * 16_000) { index -> 0.1f * kotlin.math.sin(index * 0.05f) }) }
        val sizes = mutableListOf<Int>()
        val outcome = AudioFileDecoder.decode(context, uri) { sizes += it.size; true }
        assertEquals(DecodeOutcome.Finished, outcome)
        assertTrue("${sizes.size} pieces", sizes.size in (minutes * 60 - 30)..(minutes * 60 + 1))
        assertTrue("largest piece ${sizes.max()}", sizes.max() <= 3 * 16_000)
        assertTrue("${sizes.sum()} samples", kotlin.math.abs(sizes.sum() - minutes * 60 * 16_000) < 2_000)
    }

    @Test
    fun startingPutsTheWorkingScreenUpAtOnceSoASecondTapCannotStartASecondJob() {
        val uri = lecture(240, "Tap twice.wav")
        share(uri, "audio/wav").use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(TRANSCRIBE_START).fetchSemanticsNodes().isNotEmpty() }
            var right = false
            instrumentation.runOnMainSync {
                transcriber.start()
                right = transcriber.state.value is FileTranscription.Working
                transcriber.start()
            }
            assertTrue("no working state right after start", right)
            waitFor(300_000, "work to begin") { (transcriber.state.value as? FileTranscription.Working)?.stage == FileTranscription.Working.Stage.Transcribing }
            compose.onNodeWithTag(TRANSCRIBE_CANCEL).performClick()
            waitFor(15_000, "the stop") { transcriber.state.value is FileTranscription.Ready }
            Thread.sleep(2_000)
            assertTrue("a second job kept writing", transcriber.state.value is FileTranscription.Ready)
        }
    }

    @Test
    fun stoppingRightAfterStartingStillBringsTheReadyPageBack() {
        val uri = lecture(5, "Stop at once.wav")
        share(uri, "audio/wav").use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(TRANSCRIBE_START).fetchSemanticsNodes().isNotEmpty() }
            instrumentation.runOnMainSync {
                transcriber.start()
                transcriber.cancel()
            }
            waitFor(15_000, "the Ready page after a stop before the work began") { transcriber.state.value is FileTranscription.Ready }
            Thread.sleep(2_000)
            assertTrue("the screen did not stay on the Ready page", transcriber.state.value is FileTranscription.Ready)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(TRANSCRIBE_START).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun aLateProbeOfAClosedFileDoesNotBringTheScreenBack() {
        val uri = lecture(5, "Closed early.wav")
        val broken = Uri.parse("content://media/external/audio/media/0")
        instrumentation.runOnMainSync {
            transcriber.open(broken)
            transcriber.close()
        }
        Thread.sleep(3_000)
        assertEquals(FileTranscription.Idle, transcriber.state.value)
        instrumentation.runOnMainSync {
            transcriber.open(broken)
            transcriber.open(uri)
        }
        waitFor(30_000, "the second file's Ready page") { transcriber.state.value is FileTranscription.Ready }
        Thread.sleep(3_000)
        assertTrue("the first file's late result replaced the second's", transcriber.state.value is FileTranscription.Ready)
    }

    @Test
    fun aSharedRecordingOnAColdLaunchDoesNotStartLiveCaptions() {
        app.captions.launchHandled = false
        val uri = lecture(5, "Cold share.wav")
        share(uri, "audio/wav").use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(TRANSCRIBE_START).fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(3_000)
            assertTrue("captions started under the recording page", !app.captions.captionsActive())
        }
    }

    @Test
    fun historyOffersToTranscribeARecording() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("History").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-transcribe").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Transcribe a recording").assertExists()
        }
    }

    @Test
    fun aVoiceNoteWithNoModelOnThePhoneOffersTheNormalDownload() {
        val aside = ModelFilesAside(context.filesDir)
        aside.hold()
        try {
            val uri = lecture(5, "Needs a model.wav")
            share(uri, "audio/wav").use {
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("transcribe-needs-model").fetchSemanticsNodes().isNotEmpty() }
                val ready = transcriber.state.value as FileTranscription.Ready
                assertEquals(PhoneModelCatalog.OZEN_HEBREW_FILE, ready.toDownload.file)
                assertEquals(null, ready.model)
            }
        } finally {
            aside.putBack()
        }
    }

    @Test
    fun aMinuteOfHebrewSpeechTimedWithTheOzenModel() {
        val uri = lecture(60, "One minute.wav")
        share(uri, "audio/wav").use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(TRANSCRIBE_START).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(TRANSCRIBE_START).performClick()
            val started = SystemClock.elapsedRealtime()
            waitFor(900_000, "the transcript") { transcriber.state.value is FileTranscription.Done }
            val done = transcriber.state.value as FileTranscription.Done
            saved += done.recordId!!
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            Log.i("OzenTranscribe", "TIMING: 60 s of Hebrew in %.1f s with %s, %d lines".format(seconds, done.model.file, done.lines.size))
            Log.i("OzenTranscribe", "LINES: " + done.lines.joinToString(" | ") { TranscriptFormats.clock(it.startSeconds) + " " + it.text })
            val reference = File(context.filesDir, "device-test/clip.txt")
            if (reference.isFile) {
                val heard = done.lines.filter { it.startSeconds < 52 }.joinToString(" ") { it.text }
                Log.i("OzenTranscribe", "WER over the first 52 s: %.1f%%".format(DeviceClips.wordErrorRate(reference.readText(), heard) * 100))
            }
        }
    }
}
